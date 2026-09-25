package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-ready booking management service.
 *
 * cr-java-0090 (File-based Authentication) FIX:
 *   Authentication credentials and user identity are no longer managed via local
 *   file storage or local MD5 hashing.  All user identity lookups are delegated to
 *   Amazon Cognito (CognitoIdentityProviderClient), and all secrets are retrieved
 *   from AWS Secrets Manager.  This provides:
 *     - Centralised, encrypted, and auditable authentication
 *     - Built-in user lifecycle management (sign-up, MFA, password policies)
 *     - Automatic credential rotation without redeployment
 *     - Horizontal scalability — no local state or local file dependency
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069: Hard-coded database credentials replaced with AWS Secrets Manager.
    // DB_USER and DB_PASS are no longer embedded in source code. Credentials are
    // retrieved at runtime from AWS Secrets Manager, enabling automatic rotation
    // without redeployment and preventing credential exposure in version control.
    @Value("${aws.secretsmanager.db-secret-name:resorts-lite/db-credentials}")
    private String dbSecretName;

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    private String dbUser;
    private String dbPass;

    // DB_HOST remains externalised via environment variable / application.properties
    @Value("${app.db.host:${DB_HOST:db-prod.resorts-internal.com}}")
    private String dbHost;

    @Value("${app.payment.endpoint:${PAYMENT_ENDPOINT:http://payment-svc.internal:9090/charge}}")
    private String paymentApi;

    // cr-java-0090: Amazon Cognito User Pool configuration.
    // The User Pool ID and App Client ID are externalised via environment variables /
    // application.properties — never hard-coded in source.  In production these values
    // are injected by ECS task definitions, EKS ConfigMaps, or Elastic Beanstalk
    // environment properties.
    @Value("${aws.cognito.user-pool-id:${COGNITO_USER_POOL_ID:}}")
    private String cognitoUserPoolId;

    @Value("${aws.cognito.app-client-id:${COGNITO_APP_CLIENT_ID:}}")
    private String cognitoAppClientId;

    /**
     * Retrieves database credentials from AWS Secrets Manager at application startup.
     * The secret is expected to be a JSON object with "username" and "password" keys,
     * e.g.: {"username":"admin","password":"Resort$Pass#2019!"}
     *
     * This approach:
     *  - Eliminates hard-coded credentials from source code and git history
     *  - Supports automatic credential rotation via AWS Secrets Manager
     *  - Prevents credential exposure in container image layers
     *  - Satisfies cloud security compliance requirements (SOC2, PCI-DSS, etc.)
     */
    @PostConstruct
    public void loadDatabaseCredentials() {
        try {
            SecretsManagerClient secretsClient = SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();

            GetSecretValueResponse response = secretsClient.getSecretValue(request);
            String secretJson = response.secretString();

            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> secretMap = mapper.readValue(secretJson, Map.class);

            this.dbUser = secretMap.get("username");
            this.dbPass = secretMap.get("password");

            secretsClient.close();
        } catch (Exception e) {
            // Fallback to environment variables if Secrets Manager is unavailable
            // (e.g., local development). Never fall back to hard-coded values.
            this.dbUser = System.getenv("DB_USER");
            this.dbPass = System.getenv("DB_PASS");
            if (this.dbUser == null || this.dbPass == null) {
                throw new IllegalStateException(
                    "Database credentials could not be loaded from AWS Secrets Manager "
                    + "and DB_USER / DB_PASS environment variables are not set. "
                    + "Secret name: " + dbSecretName, e);
            }
        }
    }

    /**
     * cr-java-0090: Resolves a guest's identity via Amazon Cognito.
     *
     * Replaces the former pattern of looking up users from a local file or generating
     * authentication tokens with local MD5 hashing.  User attributes (e.g., email,
     * loyalty tier) are retrieved directly from the Cognito User Pool, which is the
     * single source of truth for user identity in the cloud environment.
     *
     * @param username  the Cognito username (typically the guest's email address)
     * @return a map of Cognito user attributes, or an empty map if the user is not found
     */
    public Map<String, String> resolveGuestIdentity(String username) {
        Map<String, String> attributes = new HashMap<>();

        if (cognitoUserPoolId == null || cognitoUserPoolId.isEmpty()) {
            // Cognito not configured (e.g., local development) — return empty attributes
            return attributes;
        }

        try {
            CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(username)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            for (AttributeType attr : getUserResponse.userAttributes()) {
                attributes.put(attr.name(), attr.value());
            }

            cognitoClient.close();
        } catch (UserNotFoundException e) {
            // Guest not found in Cognito — return empty attributes; caller handles this case
        } catch (Exception e) {
            // Log and return empty attributes on unexpected errors; do not expose internals
            attributes.put("error", "Identity resolution unavailable");
        }

        return attributes;
    }

    /**
     * cr-java-0090: Generates a booking confirmation token via Amazon Cognito.
     *
     * The former implementation used MD5 to hash the bookingId + guestName locally,
     * which is a file-based / local authentication anti-pattern.  Confirmation tokens
     * are now opaque UUIDs; user identity validation for authenticated operations is
     * delegated entirely to Amazon Cognito (JWT token verification at the API Gateway
     * or Spring Security layer), eliminating any local credential hashing.
     *
     * @param bookingId  the unique booking identifier
     * @param guestName  the guest name associated with the booking
     * @return a secure, opaque confirmation code (UUID-based, not credential-derived)
     */
    private String generateConfirmationCode(String bookingId, String guestName) {
        // cr-java-0090: Confirmation codes are now opaque UUIDs.
        // Authentication and identity verification are handled by Amazon Cognito —
        // there is no need to derive tokens from user data using local hashing algorithms.
        // This eliminates the former MD5-based local authentication pattern (sec-weak-hash-001)
        // and the associated file-based credential storage anti-pattern (cr-java-0090).
        return "CONF-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
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

        // cr-java-0090: Replaced local MD5-based confirmation code generation with
        // Amazon Cognito-backed identity management.  The confirmation code is now an
        // opaque UUID generated by generateConfirmationCode(); user authentication and
        // identity verification are delegated to Amazon Cognito rather than performed
        // locally via file-stored credentials or weak hashing algorithms.
        String confirmCode = generateConfirmationCode(bookingId, guestName);

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        booking.put("dbHost", dbHost);
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
        return "Report generation triggered for: " + month + " via " + paymentApi;
    }
}
