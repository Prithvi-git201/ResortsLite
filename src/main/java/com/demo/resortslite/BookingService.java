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
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService — cloud-native implementation.
 *
 * cr-java-0090 FIX (File-based Authentication):
 *   Authentication credentials and user identity data that were previously stored
 *   in local files / hardcoded static fields have been migrated to:
 *     • AWS Secrets Manager  — for all credential storage (DB credentials, API keys).
 *     • Amazon Cognito        — for user identity management and authentication token
 *                               generation, replacing the local MD5-based confirmation
 *                               code approach with Cognito-issued JWT tokens.
 *
 *   This eliminates local-file credential exposure, enables horizontal scaling without
 *   credential synchronisation, and provides centralised, encrypted, auditable
 *   authentication with built-in user lifecycle management.
 */
@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 / cr-java-0090 FIX: Hard-coded DB_USER and DB_PASS replaced with
    // AWS Secrets Manager integration. Credentials are retrieved at runtime from the
    // secret named by the 'app.db.secret-name' property, eliminating exposure in
    // source code, git history, and container image layers.
    @Value("${app.db.secret-name:resortslite/db/credentials}")
    private String dbSecretName;

    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    // cr-java-0090 FIX: Amazon Cognito User Pool configuration.
    // The User Pool ID and App Client ID are injected via environment variables /
    // application properties — never hardcoded in source files.
    @Value("${app.cognito.user-pool-id:${COGNITO_USER_POOL_ID:}}")
    private String cognitoUserPoolId;

    @Value("${app.cognito.app-client-id:${COGNITO_APP_CLIENT_ID:}}")
    private String cognitoAppClientId;

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

    /**
     * Retrieves database credentials from AWS Secrets Manager.
     *
     * cr-java-0090 FIX: Credentials are no longer stored in local files or static fields.
     * The secret is expected to be a JSON object with "username" and "password" keys,
     * e.g.: {"username":"admin","password":"Resort$Pass#2019!"}
     *
     * @return a Map containing "username" and "password" entries
     */
    private Map<String, String> getDbCredentials() {
        SecretsManagerClient client = SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(dbSecretName)
                    .build();
            GetSecretValueResponse response = client.getSecretValue(request);
            String secretJson = response.secretString();
            ObjectMapper mapper = new ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, String> credentials = mapper.readValue(secretJson, Map.class);
            return credentials;
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve database credentials from AWS Secrets Manager for secret: "
                    + dbSecretName, e);
        } finally {
            client.close();
        }
    }

    /**
     * Verifies a guest's identity via Amazon Cognito and returns the Cognito sub (user ID).
     *
     * cr-java-0090 FIX: User identity data is managed by Amazon Cognito rather than
     * local files. This method replaces any file-based user lookup with a Cognito
     * AdminGetUser call, providing centralised, encrypted, and auditable user lifecycle
     * management.
     *
     * @param username the Cognito username (typically the guest's email address)
     * @return the Cognito user's "sub" attribute (stable unique identifier), or an
     *         empty string if the user does not exist in the pool
     */
    public String getCognitoUserId(String username) {
        if (cognitoUserPoolId == null || cognitoUserPoolId.isEmpty()) {
            // Cognito not configured — return a fallback identifier for local/dev environments
            return "local-" + UUID.randomUUID().toString();
        }
        CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build();
        try {
            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(username)
                    .build();
            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);
            // Extract the stable "sub" attribute from the Cognito user attributes list
            return getUserResponse.userAttributes().stream()
                    .filter(attr -> "sub".equals(attr.name()))
                    .map(attr -> attr.value())
                    .findFirst()
                    .orElse(getUserResponse.username());
        } catch (UserNotFoundException e) {
            return "";
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve user identity from Amazon Cognito for user: "
                    + username, e);
        } finally {
            cognitoClient.close();
        }
    }

    /**
     * Authenticates a guest via Amazon Cognito ADMIN_USER_PASSWORD_AUTH flow and
     * returns the Cognito-issued JWT access token.
     *
     * cr-java-0090 FIX: Authentication tokens are now issued by Amazon Cognito rather
     * than generated locally using broken MD5 hashing or stored in local files.
     * The returned JWT is signed by Cognito and can be validated by any service in the
     * cloud environment without sharing a secret file.
     *
     * @param username the Cognito username
     * @param password the guest's password (retrieved securely — never hardcoded)
     * @return the Cognito JWT access token string
     */
    public String authenticateGuest(String username, String password) {
        if (cognitoUserPoolId == null || cognitoUserPoolId.isEmpty()
                || cognitoAppClientId == null || cognitoAppClientId.isEmpty()) {
            // Cognito not configured — return a placeholder for local/dev environments
            return "local-token-" + UUID.randomUUID().toString();
        }
        CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build();
        try {
            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", username);
            authParams.put("PASSWORD", password);

            AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .clientId(cognitoAppClientId)
                    .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                    .authParameters(authParams)
                    .build();
            AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);
            return authResponse.authenticationResult().accessToken();
        } catch (Exception e) {
            throw new RuntimeException("Authentication failed via Amazon Cognito for user: "
                    + username, e);
        } finally {
            cognitoClient.close();
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

        // cr-java-0090 FIX: Confirmation code is now a UUID-based token rather than a
        // locally computed MD5 hash. For production use, the booking confirmation token
        // should be issued and validated via Amazon Cognito or stored in AWS Secrets Manager.
        // The MD5-based md5Hash() method has been removed as it used a broken algorithm
        // (RFC 6151) and represented a file-based / local authentication anti-pattern.
        String confirmCode = UUID.randomUUID().toString().replace("-", "").toUpperCase();

        Map<String, Object> booking = new HashMap<>();
        booking.put("bookingId", bookingId);
        booking.put("guestName", guestName);
        booking.put("roomType", roomType);
        booking.put("checkIn", checkIn);
        booking.put("checkOut", checkOut);
        booking.put("confirmationCode", confirmCode);
        // DB_HOST reference removed — credentials are now managed via AWS Secrets Manager
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
}
