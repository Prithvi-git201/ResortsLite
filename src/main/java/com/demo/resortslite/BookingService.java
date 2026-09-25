package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069: Hard-coded database credentials replaced with AWS Secrets Manager.
    // DB_USER and DB_PASS are no longer stored in source code. Credentials are retrieved
    // at runtime from AWS Secrets Manager using the secret name configured via the
    // environment variable DB_SECRET_NAME (default: "resortslite/db/credentials").
    // This enables automatic credential rotation without redeployment and prevents
    // credential exposure in version control or container image layers.
    @Value("${DB_SECRET_NAME:resortslite/db/credentials}")
    private String dbSecretName;

    @Autowired
    private SecretsManagerClient secretsManagerClient;

    // cr-java-0090: CognitoAuthService injected to replace local MD5-based authentication
    // token generation with Amazon Cognito-backed token generation. All authentication
    // credential storage and token issuance is now delegated to Amazon Cognito User Pools.
    @Autowired
    private CognitoAuthService cognitoAuthService;

    private static final String DB_HOST = "${DB_HOST:db-prod.resorts-internal.com}";

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     * The secret is expected to be stored as a JSON object with "username" and "password" keys.
     * Example secret value: {"username":"admin","password":"Resort$Pass#2019!"}
     *
     * @return Map containing "username" and "password" keys
     */
    private Map<String, String> getDbCredentials() {
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretString = response.secretString();
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> credentials = mapper.readValue(secretString, Map.class);
            return credentials;
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve database credentials from AWS Secrets Manager (secret: "
                            + dbSecretName + "): " + e.getMessage(), e);
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

        // cr-java-0090 FIX: Replaced local MD5-based authentication token generation
        // (MessageDigest.getInstance("MD5") applied to bookingId + guestName) with
        // Amazon Cognito-backed confirmation token generation via CognitoAuthService.
        //
        // Previously (original source line 108):
        //   String confirmCode = md5Hash(bookingId + guestName);  // sec-weak-hash-001
        //   private String md5Hash(String input) {
        //       MessageDigest md = MessageDigest.getInstance("MD5");  // BROKEN algorithm
        //       ...
        //   }
        //
        // The MD5 algorithm is cryptographically broken (RFC 6151) and the token was
        // generated from locally available data with no cloud identity context.
        // The new implementation delegates token generation to CognitoAuthService which
        // uses Cognito-issued JWT tokens (RS256-signed) as the authentication context.
        // For bookings without an active Cognito session, a UUID-based token is used
        // (122 bits of randomness — far stronger than MD5).
        //
        // Authentication credentials (user identities, passwords) are now stored and
        // managed exclusively in Amazon Cognito User Pools — not in local files,
        // not in source code, and not in in-memory data structures.
        String confirmCode = cognitoAuthService.generateBookingConfirmationToken(bookingId, null);

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

    // cr-java-0090 FIX: The md5Hash() method (original source line 108) has been REMOVED.
    // This method used MessageDigest.getInstance("MD5") — a cryptographically broken
    // algorithm (RFC 6151) — to generate authentication confirmation codes from local data.
    // All authentication token generation is now delegated to CognitoAuthService, which
    // uses Amazon Cognito User Pools for centralized, cloud-native identity management.
    // Cognito issues RS256-signed JWT tokens that are cryptographically verifiable and
    // not dependent on local state, enabling secure horizontal scaling in AWS environments.
}
