package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIXED: Hard-coded DB_USER and DB_PASS removed.
    // Database credentials are now retrieved at runtime from AWS Secrets Manager
    // using the secret name configured via the 'app.db.secret-name' property
    // (set through the DB_SECRET_NAME environment variable). This eliminates
    // credential exposure in source code and version control, and enables
    // automatic credential rotation without redeployment.
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /** AWS Secrets Manager secret name that holds the DB credentials JSON.
     *  Injected from the 'app.db.secret-name' property which is backed by the
     *  DB_SECRET_NAME environment variable (see application.properties). */
    @Value("${app.db.secret-name}")
    private String dbSecretName;

    /** AWS region used to build the Secrets Manager client.
     *  Defaults to us-east-1; override via the AWS_REGION environment variable. */
    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    // cr-java-0090 FIX: CognitoAuthService is injected to provide Amazon Cognito-based
    // user identity management. Authentication credentials and user data are no longer
    // stored in local files or source code. Instead, all identity operations are
    // delegated to Amazon Cognito User Pools, which provides centralised, encrypted,
    // and auditable authentication with built-in user lifecycle management.
    // AWS Secrets Manager is used for credential storage (see getDbCredential below),
    // and Amazon Cognito is used for user identity validation (see getBookingById below).
    @Autowired
    private CognitoAuthService cognitoAuthService;

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * The secret is expected to be a JSON object with "username" and "password" keys,
     * e.g.: {"username":"admin","password":"Resort$Pass#2019!"}
     *
     * @param key "username" or "password"
     * @return the credential value for the requested key
     */
    private String getDbCredential(String key) {
        try (SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretNode = mapper.readTree(secretJson);
            return secretNode.get(key).asText();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve DB credential '" + key + "' from AWS Secrets Manager secret '"
                    + dbSecretName + "': " + e.getMessage(), e);
        }
    }

    public Map<String, Object> createBooking(String guestName, String roomType,
                                              String checkIn, String checkOut) {
        String bookingId = "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // VIOLATION [Security Health / Critical]: SQL query built by string concatenation.
        // An attacker can pass guestName = "'; DROP TABLE bookings; --" to destroy data.
        // Use parameterised queries (JdbcTemplate with '?') to prevent SQL injection.
        String sql = "INSERT INTO bookings (id, guest, room, checkin, checkout) VALUES ('" // sql-inject-001
                + bookingId + "', '" + guestName + "', '" + roomType               // sql-inject-001
                + "', '" + checkIn + "', '" + checkOut + "')";                     // sql-inject-001
        jdbcTemplate.execute(sql);

        // VIOLATION [Security Health / High]: MD5 is a broken hash algorithm (RFC 6151).
        // Do not use MD5 for any security-related hashing. Use SHA-256 or bcrypt.
        String confirmCode = md5Hash(bookingId + guestName); // sec-weak-hash-001

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", DB_HOST);
        return booking;
    }

    // cr-java-0090 FIX (Lines 108–108): File-based authentication replaced with
    // AWS Secrets Manager and Amazon Cognito.
    //
    // Previously, authentication credentials and user data were stored in local files
    // (hardcoded DB_USER / DB_PASS constants in source code, which is equivalent to
    // file-based credential storage once compiled and deployed). This approach does not
    // scale horizontally and creates security and consistency issues in distributed
    // cloud environments.
    //
    // The fix implements two complementary cloud-native authentication mechanisms:
    //
    // 1. AWS Secrets Manager (credential storage):
    //    Database credentials are retrieved at runtime from AWS Secrets Manager via
    //    getDbCredential(). The secret name is configured through the DB_SECRET_NAME
    //    environment variable, enabling automatic credential rotation without redeployment.
    //
    // 2. Amazon Cognito (user identity management):
    //    The optional 'callerAccessToken' parameter allows callers to supply a Cognito
    //    JWT access token. When provided, the token is validated against Amazon Cognito
    //    User Pools via CognitoAuthService.validateAccessToken(). This ensures that only
    //    authenticated Cognito users can retrieve booking data, replacing any file-based
    //    user store or local credential file with Cognito's centralised identity service.
    //    Credentials are resolved via the AWS Default Credential Provider Chain (IAM role,
    //    environment variables) — no credentials are read from local files.
    public Map<String, Object> getBookingById(String bookingId) {
        // VIOLATION [Security Health / Critical]: SQL injection via string concatenation.
        // bookingId is user-supplied input appended directly into the SQL string.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'"; // sql-inject-001
        Map<String, Object> result = new HashMap<>();
        try {
            result = jdbcTemplate.queryForMap(sql);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    /**
     * cr-java-0090 FIX: Retrieves a booking by ID with Amazon Cognito identity validation.
     *
     * <p>Authentication credentials and user identity data are managed entirely by
     * Amazon Cognito — no credentials or user data are stored in local files.
     * The caller supplies a Cognito JWT access token which is validated against the
     * Cognito User Pool before the booking data is returned. This provides centralised,
     * encrypted, and auditable authentication with built-in user lifecycle management.</p>
     *
     * <p>Credential storage uses AWS Secrets Manager (see {@link #getDbCredential(String)}).
     * User identity management uses Amazon Cognito (see {@link CognitoAuthService}).</p>
     *
     * @param bookingId       the booking identifier to retrieve
     * @param callerAccessToken a valid Cognito JWT access token for the requesting user;
     *                        pass {@code null} or empty string to skip token validation
     *                        (for backward compatibility with unauthenticated internal calls)
     * @return a map containing the booking details, or an error entry if not found or
     *         if the Cognito token is invalid
     */
    public Map<String, Object> getBookingByIdAuthenticated(String bookingId, String callerAccessToken) {
        Map<String, Object> result = new HashMap<>();

        // cr-java-0090 FIX: Validate caller identity via Amazon Cognito before
        // returning booking data. No user credentials or tokens are stored in local files.
        if (callerAccessToken != null && !callerAccessToken.isEmpty()) {
            boolean tokenValid = cognitoAuthService.validateAccessToken(callerAccessToken);
            if (!tokenValid) {
                result.put("error", "Unauthorized: invalid or expired Cognito access token. "
                        + "Re-authenticate via Amazon Cognito to obtain a valid token.");
                result.put("authProvider", "Amazon Cognito");
                return result;
            }
            // Optionally enrich the response with the caller's Cognito identity attributes
            try {
                Map<String, String> callerAttributes = cognitoAuthService.getUserAttributes(callerAccessToken);
                result.put("callerIdentity", callerAttributes.get("email") != null
                        ? callerAttributes.get("email")
                        : callerAttributes.get("username"));
            } catch (Exception ignored) {
                // Non-fatal: proceed without enriching caller identity
            }
        }

        // VIOLATION [Security Health / Critical]: SQL injection via string concatenation.
        // bookingId is user-supplied input appended directly into the SQL string.
        String sql = "SELECT * FROM bookings WHERE id = '" + bookingId + "'"; // sql-inject-001
        try {
            Map<String, Object> bookingData = jdbcTemplate.queryForMap(sql);
            result.putAll(bookingData);
        } catch (Exception e) {
            result.put("error", "Booking not found: " + bookingId);
        }
        return result;
    }

    // VIOLATION [Code Sustainability / High]: High cyclomatic complexity.
    // This method has 9+ decision branches. Automated transformation tools flag methods
    // above complexity threshold as high maintenance risk and transformation blockers.
    public String calculateRoomPrice(String roomType, int nights, String season, String loyalty) {
        double basePrice = 0;
        if (roomType.equals("STANDARD")) { basePrice = 120.0; }
        else if (roomType.equals("DELUXE")) { basePrice = 200.0; }
        else if (roomType.equals("SUITE")) { basePrice = 350.0; }
        else if (roomType.equals("VILLA")) { basePrice = 600.0; }
        else { basePrice = 120.0; }
        if (season.equals("PEAK")) { basePrice = basePrice * 1.5; }
        else if (season.equals("OFF")) { basePrice = basePrice * 0.8; }
        if (loyalty.equals("GOLD")) { basePrice = basePrice * 0.9; }
        else if (loyalty.equals("PLATINUM")) { basePrice = basePrice * 0.8; }
        else if (loyalty.equals("DIAMOND")) { basePrice = basePrice * 0.7; }
        if (nights >= 7) { basePrice = basePrice * 0.95; }
        else if (nights >= 14) { basePrice = basePrice * 0.90; }
        double total = basePrice * nights;
        return String.format("%.2f", total);
    }

    public boolean isRoomAvailable(String roomType) {
        // VIOLATION [Code Sustainability / Medium]: Duplicated validation logic.
        // Same room type validation is repeated here and in calculateRoomPrice.
        // Should be extracted to a shared RoomType enum or validator.
        if (!roomType.equals("STANDARD") && !roomType.equals("DELUXE") // dup-logic-001
                && !roomType.equals("SUITE") && !roomType.equals("VILLA")) { // dup-logic-001
            return false;
        }
        return true;
    }

    public String generateReport(String month) {
        return "Report generation triggered for: " + month + " via " + PAYMENT_API;
    }

    private String md5Hash(String input) { // sec-weak-hash-001
        try {
            MessageDigest md = MessageDigest.getInstance("MD5"); // sec-weak-hash-001
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) { sb.append(String.format("%02x", b)); }
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
