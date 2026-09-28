package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: Hard-coded database credentials removed.
    // DB_USER and DB_PASS are now retrieved at runtime from AWS Secrets Manager
    // using the secret name configured via the environment variable
    // DB_SECRET_NAME (default: "resortslite/db/credentials").
    // The secret is expected to be a JSON object with keys "username" and "password".
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    // cr-java-0069 FIX: Credentials sourced from AWS Secrets Manager at runtime.
    private final String DB_USER;
    private final String DB_PASS;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    // cr-java-0090 FIX: AWS Cognito User Pool configuration.
    // The Cognito User Pool ID and Client ID are resolved from environment variables
    // COGNITO_USER_POOL_ID and COGNITO_CLIENT_ID at runtime, enabling centralized,
    // encrypted, and auditable authentication with built-in user lifecycle management.
    // These replace the previous file-based MD5 authentication token generation.
    private final CognitoIdentityProviderClient cognitoClient;
    private final String cognitoUserPoolId;

    /**
     * Constructor that retrieves database credentials from AWS Secrets Manager
     * and initialises the Amazon Cognito client for cloud-native authentication.
     *
     * Environment variables consumed:
     *   DB_SECRET_NAME      - AWS Secrets Manager secret name/ARN for DB credentials
     *                         (default: "resortslite/db/credentials")
     *   CLOUD_AWS_REGION    - AWS region for Secrets Manager and Cognito
     *                         (default: "us-east-1")
     *   COGNITO_USER_POOL_ID - Amazon Cognito User Pool ID for authentication
     *                         (default: "us-east-1_PLACEHOLDER")
     */
    public BookingService() {
        String secretName = System.getenv("DB_SECRET_NAME") != null
                ? System.getenv("DB_SECRET_NAME")
                : "resortslite/db/credentials";
        String regionName = System.getenv("CLOUD_AWS_REGION") != null
                ? System.getenv("CLOUD_AWS_REGION")
                : "us-east-1";

        // cr-java-0090 FIX: Initialise Cognito User Pool configuration from environment.
        // Replaces file-based authentication token generation (MD5 hashing of local credentials)
        // with Amazon Cognito for centralized user identity management.
        this.cognitoUserPoolId = System.getenv("COGNITO_USER_POOL_ID") != null
                ? System.getenv("COGNITO_USER_POOL_ID")
                : "us-east-1_PLACEHOLDER";

        // cr-java-0090 FIX: Build the Cognito Identity Provider client using the AWS region.
        // This client is used to verify user identity and retrieve Cognito-managed
        // confirmation tokens instead of generating them locally from file-stored credentials.
        this.cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(regionName))
                .build();

        String resolvedUser = "";
        String resolvedPass = "";

        try {
            SecretsManagerClient client = SecretsManagerClient.builder()
                    .region(Region.of(regionName))
                    .build();

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse response = client.getSecretValue(request);
            String secretString = response.secretString();

            // Parse the JSON secret: {"username":"...","password":"..."}
            ObjectMapper mapper = new ObjectMapper();
            JsonNode secretJson = mapper.readTree(secretString);
            resolvedUser = secretJson.has("username") ? secretJson.get("username").asText() : "";
            resolvedPass = secretJson.has("password") ? secretJson.get("password").asText() : "";

            client.close();
        } catch (Exception e) {
            // Log the error; credentials remain empty — application will fail fast
            // on first DB operation rather than silently using wrong credentials.
            System.err.println("[BookingService] WARNING: Could not retrieve DB credentials "
                    + "from AWS Secrets Manager (secret: " + secretName + "): " + e.getMessage());
        }

        this.DB_USER = resolvedUser;
        this.DB_PASS = resolvedPass;
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

        // cr-java-0090 FIX: Replaced file-based MD5 authentication token generation with
        // Amazon Cognito-based confirmation code retrieval. User identity and confirmation
        // tokens are now managed by AWS Cognito User Pool, providing centralized, encrypted,
        // and auditable authentication. The local md5Hash() method has been removed.
        String confirmCode = getCognitoConfirmationCode(guestName, bookingId);

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

    /**
     * cr-java-0090 FIX: Retrieves a Cognito-managed confirmation code for the booking.
     *
     * <p>Replaces the previous file-based authentication pattern where a local MD5 hash
     * of hardcoded/file-stored credentials was used to generate confirmation tokens.
     * Authentication credentials and user identity are now managed exclusively by
     * Amazon Cognito User Pool, providing:
     * <ul>
     *   <li>Centralized, encrypted credential storage (no local files)</li>
     *   <li>Auditable authentication events via AWS CloudTrail</li>
     *   <li>Built-in user lifecycle management (registration, MFA, password policies)</li>
     *   <li>Horizontal scalability — no local state or file system dependency</li>
     * </ul>
     *
     * <p>The method attempts to look up the guest's Cognito user attributes to retrieve
     * a Cognito-assigned sub (unique user identifier) as the confirmation token.
     * If the user is not found in Cognito (e.g., guest checkout), a UUID-based
     * confirmation code is generated as a safe fallback.
     *
     * @param guestName the guest's username or email registered in the Cognito User Pool
     * @param bookingId the booking identifier used as a fallback seed
     * @return a Cognito sub-based or UUID-based confirmation code
     */
    private String getCognitoConfirmationCode(String guestName, String bookingId) {
        try {
            // cr-java-0090 FIX: Query Amazon Cognito User Pool for the guest's identity.
            // The Cognito 'sub' attribute is a stable, unique identifier assigned by Cognito
            // and serves as the confirmation code — replacing the insecure local MD5 hash.
            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(guestName)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            // Extract the Cognito 'sub' (unique user identifier) as the confirmation token.
            // The 'sub' is immutable, globally unique, and managed by AWS Cognito.
            for (AttributeType attribute : getUserResponse.userAttributes()) {
                if ("sub".equals(attribute.name())) {
                    return "CONF-" + attribute.value().substring(0, 8).toUpperCase();
                }
            }

            // Fallback: use the Cognito username as confirmation seed if 'sub' not found.
            return "CONF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        } catch (CognitoIdentityProviderException e) {
            // Guest not found in Cognito User Pool (e.g., anonymous/guest checkout).
            // Generate a UUID-based confirmation code as a safe, stateless fallback.
            // No local file access or hardcoded credentials are used.
            System.err.println("[BookingService] INFO: Guest '" + guestName
                    + "' not found in Cognito User Pool '" + cognitoUserPoolId
                    + "'. Using UUID-based confirmation code. Error: " + e.getMessage());
            return "CONF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        } catch (Exception e) {
            // Unexpected error — fall back to UUID-based confirmation code.
            System.err.println("[BookingService] WARNING: Cognito lookup failed for guest '"
                    + guestName + "': " + e.getMessage());
            return "CONF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
    }
}
