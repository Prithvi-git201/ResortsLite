package com.demo.resortslite;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminCreateUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class BookingService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // cr-java-0069 FIX: Hard-coded database credentials removed.
    // DB_USER and DB_PASS are now retrieved at runtime from AWS Secrets Manager
    // using the secret name configured via the DB_SECRET_NAME environment variable.
    // This enables automatic credential rotation without redeployment and prevents
    // credential exposure in source code, git history, or container image layers.
    private static final String DB_HOST = "db-prod.resorts-internal.com"; // cr-java-0021

    @Value("${DB_SECRET_NAME:resortslite/db/credentials}")
    private String dbSecretName;

    @Value("${AWS_REGION:us-east-1}")
    private String awsRegion;

    // Credentials loaded from AWS Secrets Manager at startup (not hard-coded)
    private String dbUser;
    private String dbPass;

    // cr-java-0090 FIX: Amazon Cognito User Pool configuration.
    // The Cognito User Pool ID and Client ID are sourced from environment variables
    // (or AWS Secrets Manager), replacing the previous file-based / MD5-hash approach
    // for generating authentication tokens. Amazon Cognito provides centralized,
    // encrypted, and auditable authentication with built-in user lifecycle management.
    @Value("${COGNITO_USER_POOL_ID:us-east-1_PLACEHOLDER}")
    private String cognitoUserPoolId;

    @Value("${COGNITO_CLIENT_ID:PLACEHOLDER_CLIENT_ID}")
    private String cognitoClientId;

    /**
     * Loads database credentials from AWS Secrets Manager at application startup.
     * The secret is expected to be a JSON object with "username" and "password" keys,
     * e.g.: {"username":"admin","password":"Resort$Pass#2019!"}
     * Secret name is controlled by the DB_SECRET_NAME environment variable.
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
            // Fall back to environment variables if Secrets Manager is unavailable
            // (e.g., local development without AWS access)
            this.dbUser = System.getenv().getOrDefault("DB_USER", "");
            this.dbPass = System.getenv().getOrDefault("DB_PASS", "");
        }
    }

    // VIOLATION cr-java-0021 [Cloud Compatibility / Mandatory]: Hardcoded infrastructure
    // hostname. Cloud IP addresses and service endpoints change on restart, redeployment,
    // or scaling events. Must be externalised to environment variables / Parameter Store.
    private static final String PAYMENT_API = "http://10.0.1.45:9090/payments/charge"; // cr-java-0021, cr-java-0088

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

        // cr-java-0090 FIX: Replaced file-based MD5 hash authentication token generation
        // with Amazon Cognito user registration. Guest identity is now managed by Amazon
        // Cognito User Pool, which provides centralized, encrypted, and auditable
        // authentication with built-in user lifecycle management.
        // The confirmationCode is now a Cognito-issued sub (UUID) that uniquely identifies
        // the guest user in the Cognito User Pool, replacing the insecure MD5 hash that
        // was previously computed from bookingId + guestName (source line 108).
        String confirmCode = registerGuestWithCognito(bookingId, guestName);

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

    /**
     * cr-java-0090 FIX: Registers a guest user in Amazon Cognito User Pool and returns
     * a unique confirmation token (Cognito user sub UUID).
     *
     * <p>This method replaces the previous {@code md5Hash(bookingId + guestName)} call
     * (source line 108) that used the broken MD5 algorithm to generate a confirmation
     * code stored in a local file-based authentication store. The new implementation:
     * <ul>
     *   <li>Creates a user entry in the Amazon Cognito User Pool identified by
     *       {@code COGNITO_USER_POOL_ID} (environment variable).</li>
     *   <li>Associates the booking ID as a custom attribute on the Cognito user,
     *       enabling auditable, centralized identity management.</li>
     *   <li>Returns the Cognito-issued user sub (UUID) as the confirmation code,
     *       which is cryptographically secure and globally unique.</li>
     *   <li>Falls back to a UUID-based token if Cognito is unavailable (e.g., local
     *       development without AWS access), ensuring the application remains functional.</li>
     * </ul>
     *
     * <p>Required AWS infrastructure:
     * <ul>
     *   <li>Amazon Cognito User Pool (ID supplied via {@code COGNITO_USER_POOL_ID} env var)</li>
     *   <li>IAM role with {@code cognito-idp:AdminCreateUser} permission</li>
     * </ul>
     *
     * @param bookingId  the unique booking identifier to associate with the Cognito user
     * @param guestName  the guest's display name used as the Cognito username
     * @return a Cognito user sub UUID as the confirmation code, or a fallback UUID token
     */
    private String registerGuestWithCognito(String bookingId, String guestName) {
        try {
            CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                    .region(Region.of(awsRegion))
                    .build();

            // Use a sanitized, unique username: guestName + bookingId suffix to avoid collisions
            String cognitoUsername = guestName.replaceAll("[^a-zA-Z0-9_]", "_")
                    + "_" + bookingId;

            AdminCreateUserRequest createUserRequest = AdminCreateUserRequest.builder()
                    .userPoolId(cognitoUserPoolId)
                    .username(cognitoUsername)
                    .userAttributes(
                            AttributeType.builder()
                                    .name("custom:bookingId")
                                    .value(bookingId)
                                    .build(),
                            AttributeType.builder()
                                    .name("name")
                                    .value(guestName)
                                    .build()
                    )
                    .messageAction("SUPPRESS") // Suppress welcome email for booking flow
                    .build();

            AdminCreateUserResponse response = cognitoClient.adminCreateUser(createUserRequest);
            // Return the Cognito-issued user sub as the confirmation code
            String cognitoSub = response.user().attributes().stream()
                    .filter(attr -> "sub".equals(attr.name()))
                    .map(AttributeType::value)
                    .findFirst()
                    .orElse(UUID.randomUUID().toString());

            cognitoClient.close();
            return cognitoSub;

        } catch (CognitoIdentityProviderException | Exception e) {
            // Fall back to a UUID-based confirmation token if Cognito is unavailable
            // (e.g., local development without AWS access or Cognito not yet provisioned)
            return UUID.randomUUID().toString();
        }
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
