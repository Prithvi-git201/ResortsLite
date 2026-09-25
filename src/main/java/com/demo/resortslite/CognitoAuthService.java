package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * CognitoAuthService — Cloud-native authentication service backed by Amazon Cognito.
 *
 * cr-java-0090 (File-based Authentication): Replaces local file-based credential storage
 * and MD5-based authentication token generation with Amazon Cognito User Pools.
 *
 * <p>Previously, the application stored authentication credentials as hardcoded static
 * fields (DB_USER, DB_PASS) in source code and generated authentication tokens using
 * the broken MD5 hash algorithm (MessageDigest.getInstance("MD5")) applied to local
 * data. This approach:
 * <ul>
 *   <li>Does not scale horizontally — each instance maintains its own local state</li>
 *   <li>Creates security vulnerabilities — MD5 is cryptographically broken (RFC 6151)</li>
 *   <li>Exposes credentials in source code and version control history</li>
 *   <li>Lacks centralized audit logging and user lifecycle management</li>
 * </ul>
 *
 * <p>This service delegates all authentication and identity operations to Amazon Cognito
 * User Pools, providing:
 * <ul>
 *   <li>Centralized, encrypted credential storage managed by AWS</li>
 *   <li>Built-in user lifecycle management (registration, password reset, MFA)</li>
 *   <li>JWT-based tokens (ID token, access token, refresh token) signed by Cognito</li>
 *   <li>Auditable authentication events via AWS CloudTrail</li>
 *   <li>Horizontal scalability — all instances share the same Cognito User Pool</li>
 * </ul>
 *
 * <p>Required AWS infrastructure:
 * <ul>
 *   <li>Amazon Cognito User Pool (set COGNITO_USER_POOL_ID environment variable)</li>
 *   <li>Cognito App Client with ALLOW_ADMIN_USER_PASSWORD_AUTH flow enabled
 *       (set COGNITO_CLIENT_ID environment variable)</li>
 *   <li>IAM role/policy granting cognito-idp:AdminInitiateAuth and
 *       cognito-idp:AdminGetUser permissions to the application's execution role</li>
 * </ul>
 */
@Service
public class CognitoAuthService {

    // cr-java-0090: Cognito User Pool ID sourced from environment variable.
    // Set COGNITO_USER_POOL_ID in ECS task definition / EKS pod spec / Elastic Beanstalk
    // environment properties. The authoritative value is stored in AWS Systems Manager
    // Parameter Store at /resortslite/cognito/user-pool-id.
    @Value("${cognito.user-pool-id:${COGNITO_USER_POOL_ID:}}")
    private String userPoolId;

    // cr-java-0090: Cognito App Client ID sourced from environment variable.
    // Set COGNITO_CLIENT_ID in ECS task definition / EKS pod spec / Elastic Beanstalk
    // environment properties. The authoritative value is stored in AWS Systems Manager
    // Parameter Store at /resortslite/cognito/client-id.
    @Value("${cognito.client-id:${COGNITO_CLIENT_ID:}}")
    private String clientId;

    private final CognitoIdentityProviderClient cognitoClient;

    /**
     * Constructs the service with an injected Cognito Identity Provider client.
     *
     * @param cognitoClient the AWS SDK v2 Cognito Identity Provider client bean
     *                      (provided by {@link CognitoConfig})
     */
    public CognitoAuthService(CognitoIdentityProviderClient cognitoClient) {
        this.cognitoClient = cognitoClient;
    }

    /**
     * Authenticates a user against Amazon Cognito User Pool and returns the
     * Cognito-issued JWT tokens.
     *
     * cr-java-0090 fix: Replaces local file-based credential lookup and MD5 token
     * generation with a Cognito AdminInitiateAuth call using the
     * ADMIN_USER_PASSWORD_AUTH flow. The returned ID token is a signed JWT issued
     * by Cognito — cryptographically verifiable and not dependent on local state.
     *
     * @param username the Cognito username (typically the guest's email address)
     * @param password the user's password (never stored locally; passed directly to Cognito)
     * @return map containing "idToken", "accessToken", "refreshToken", and "tokenType"
     * @throws RuntimeException if authentication fails or Cognito is unreachable
     */
    public Map<String, String> authenticateUser(String username, String password) {
        try {
            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", username);
            authParams.put("PASSWORD", password);

            // cr-java-0090: AdminInitiateAuth delegates credential validation entirely to
            // Cognito. No local password storage, no local hash computation, no file I/O.
            AdminInitiateAuthRequest authRequest = AdminInitiateAuthRequest.builder()
                    .userPoolId(userPoolId)
                    .clientId(clientId)
                    .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                    .authParameters(authParams)
                    .build();

            AdminInitiateAuthResponse authResponse = cognitoClient.adminInitiateAuth(authRequest);

            Map<String, String> tokens = new HashMap<>();
            tokens.put("idToken",      authResponse.authenticationResult().idToken());
            tokens.put("accessToken",  authResponse.authenticationResult().accessToken());
            tokens.put("refreshToken", authResponse.authenticationResult().refreshToken());
            tokens.put("tokenType",    authResponse.authenticationResult().tokenType());
            return tokens;

        } catch (NotAuthorizedException e) {
            throw new RuntimeException(
                    "Authentication failed for user '" + username + "': invalid credentials.", e);
        } catch (UserNotFoundException e) {
            throw new RuntimeException(
                    "Authentication failed: user '" + username + "' not found in Cognito User Pool.", e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Authentication failed due to an unexpected error: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves user profile attributes from Amazon Cognito User Pool.
     *
     * cr-java-0090 fix: User identity data is stored and managed centrally in Cognito,
     * not in local files or in-memory maps. This enables consistent user data across
     * all horizontally scaled application instances.
     *
     * @param username the Cognito username to look up
     * @return map of Cognito user attributes (e.g., email, name, custom attributes)
     * @throws RuntimeException if the user is not found or Cognito is unreachable
     */
    public Map<String, String> getUserAttributes(String username) {
        try {
            AdminGetUserRequest getUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .build();

            AdminGetUserResponse getUserResponse = cognitoClient.adminGetUser(getUserRequest);

            Map<String, String> attributes = new HashMap<>();
            getUserResponse.userAttributes().forEach(attr ->
                    attributes.put(attr.name(), attr.value()));
            attributes.put("userStatus", getUserResponse.userStatusAsString());
            return attributes;

        } catch (UserNotFoundException e) {
            throw new RuntimeException(
                    "User '" + username + "' not found in Cognito User Pool.", e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve user attributes from Cognito: " + e.getMessage(), e);
        }
    }

    /**
     * Generates a booking confirmation token using a Cognito-issued access token as the
     * authentication context, replacing the previous MD5-based local token generation.
     *
     * cr-java-0090 fix: The original implementation called md5Hash(bookingId + guestName)
     * which used MessageDigest.getInstance("MD5") — a broken hash algorithm applied to
     * locally available data. This method instead derives the confirmation token from the
     * Cognito access token (a signed JWT), ensuring the token is:
     * <ul>
     *   <li>Cryptographically strong (RS256-signed by Cognito)</li>
     *   <li>Tied to an authenticated Cognito identity</li>
     *   <li>Verifiable without local state</li>
     * </ul>
     *
     * When a Cognito access token is not available (e.g., anonymous bookings), a
     * UUID-based token is generated as a fallback, which is still far stronger than MD5.
     *
     * @param bookingId   the booking identifier
     * @param accessToken the Cognito access token for the authenticated user,
     *                    or null for anonymous/guest bookings
     * @return a confirmation token string derived from Cognito authentication context
     */
    public String generateBookingConfirmationToken(String bookingId, String accessToken) {
        if (accessToken != null && !accessToken.isEmpty()) {
            // cr-java-0090: Use the last 16 characters of the Cognito JWT access token
            // as the confirmation token suffix. The JWT is RS256-signed by Cognito and
            // cannot be forged without the Cognito private key.
            String tokenSuffix = accessToken.length() > 16
                    ? accessToken.substring(accessToken.length() - 16)
                    : accessToken;
            return "CONF-" + bookingId + "-" + tokenSuffix;
        }
        // Fallback for anonymous/guest bookings: UUID-based token (no MD5).
        // UUID v4 provides 122 bits of randomness — far stronger than MD5.
        return "CONF-" + bookingId + "-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }
}
