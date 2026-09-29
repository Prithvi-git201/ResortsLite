package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthRequest;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;

import java.util.HashMap;
import java.util.Map;

/**
 * CognitoAuthService — cr-java-0090 FIX
 *
 * <p>Replaces file-based authentication (credentials/user data stored in local files
 * or hardcoded in source code) with Amazon Cognito for centralised, encrypted, and
 * auditable user identity management.</p>
 *
 * <p>Authentication credentials are no longer stored in local files or source code.
 * Instead, this service delegates all authentication and user lifecycle operations
 * to Amazon Cognito User Pools, which provides:</p>
 * <ul>
 *   <li>Centralised, encrypted credential storage</li>
 *   <li>Built-in user lifecycle management (sign-up, sign-in, password reset)</li>
 *   <li>JWT-based access tokens for stateless, horizontally-scalable authentication</li>
 *   <li>Audit trails via AWS CloudTrail</li>
 *   <li>Multi-factor authentication (MFA) support</li>
 * </ul>
 *
 * <p>Required environment variables / application properties:</p>
 * <ul>
 *   <li>{@code COGNITO_USER_POOL_ID} / {@code cognito.user-pool-id} — Cognito User Pool ID</li>
 *   <li>{@code COGNITO_CLIENT_ID} / {@code cognito.client-id} — Cognito App Client ID</li>
 *   <li>{@code AWS_REGION} / {@code aws.region} — AWS region (default: us-east-1)</li>
 * </ul>
 */
@Service
public class CognitoAuthService {

    /**
     * Amazon Cognito User Pool ID.
     * Set the COGNITO_USER_POOL_ID environment variable in the ECS task definition,
     * EKS pod spec, or Elastic Beanstalk environment configuration.
     * Example: us-east-1_AbCdEfGhI
     */
    @Value("${cognito.user-pool-id:${COGNITO_USER_POOL_ID:us-east-1_placeholder}}")
    private String userPoolId;

    /**
     * Amazon Cognito App Client ID (public client — no client secret required for
     * USER_PASSWORD_AUTH flow when the app client is configured without a secret).
     * Set the COGNITO_CLIENT_ID environment variable before deploying to AWS.
     * Example: 1abc2defghij3klmnopqrstu4v
     */
    @Value("${cognito.client-id:${COGNITO_CLIENT_ID:placeholder-client-id}}")
    private String clientId;

    /**
     * AWS region used to build the Cognito Identity Provider client.
     * Defaults to us-east-1; override via the AWS_REGION environment variable.
     */
    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Authenticates a user against Amazon Cognito using the USER_PASSWORD_AUTH flow.
     *
     * <p>cr-java-0090 FIX: Credentials are validated by Cognito — they are never stored
     * in local files, source code, or application properties. Cognito returns a JWT
     * access token on successful authentication, which can be used for subsequent
     * authorised API calls.</p>
     *
     * @param username the Cognito username (typically an email address)
     * @param password the user's password (never persisted locally)
     * @return a map containing the Cognito access token, ID token, and refresh token
     * @throws RuntimeException if authentication fails (invalid credentials, user not found, etc.)
     */
    public Map<String, String> authenticateUser(String username, String password) {
        try (CognitoIdentityProviderClient cognitoClient = buildCognitoClient()) {

            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", username);
            authParams.put("PASSWORD", password);

            InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
                    .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                    .clientId(clientId)
                    .authParameters(authParams)
                    .build();

            InitiateAuthResponse authResponse = cognitoClient.initiateAuth(authRequest);

            Map<String, String> tokens = new HashMap<>();
            tokens.put("accessToken",  authResponse.authenticationResult().accessToken());
            tokens.put("idToken",      authResponse.authenticationResult().idToken());
            tokens.put("refreshToken", authResponse.authenticationResult().refreshToken());
            tokens.put("tokenType",    authResponse.authenticationResult().tokenType());
            return tokens;

        } catch (NotAuthorizedException e) {
            throw new RuntimeException(
                    "Authentication failed for user '" + username + "': invalid credentials.", e);
        } catch (UserNotFoundException e) {
            throw new RuntimeException(
                    "Authentication failed: user '" + username + "' does not exist in Cognito User Pool.", e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Cognito authentication error for user '" + username + "': " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves the currently authenticated user's profile from Amazon Cognito
     * using a valid access token.
     *
     * <p>cr-java-0090 FIX: User data is retrieved from Cognito — it is never read
     * from local files or a local user store. This ensures user data is always
     * consistent across all horizontally-scaled application instances.</p>
     *
     * @param accessToken a valid Cognito JWT access token obtained from {@link #authenticateUser}
     * @return a map of Cognito user attributes (e.g. email, sub, custom attributes)
     * @throws RuntimeException if the token is invalid or the user cannot be found
     */
    public Map<String, String> getUserAttributes(String accessToken) {
        try (CognitoIdentityProviderClient cognitoClient = buildCognitoClient()) {

            GetUserRequest getUserRequest = GetUserRequest.builder()
                    .accessToken(accessToken)
                    .build();

            GetUserResponse getUserResponse = cognitoClient.getUser(getUserRequest);

            Map<String, String> attributes = new HashMap<>();
            attributes.put("username", getUserResponse.username());
            for (AttributeType attr : getUserResponse.userAttributes()) {
                attributes.put(attr.name(), attr.value());
            }
            return attributes;

        } catch (NotAuthorizedException e) {
            throw new RuntimeException(
                    "Access token is invalid or expired. Re-authenticate via Cognito.", e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve user attributes from Cognito: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieves a user's profile from Amazon Cognito using admin credentials
     * (IAM role-based access — no file-based credentials required).
     *
     * <p>cr-java-0090 FIX: Admin operations use the AWS Default Credential Provider Chain
     * (IAM role, environment variables) — no credentials are stored in local files.</p>
     *
     * @param username the Cognito username to look up
     * @return a map of Cognito user attributes for the specified user
     * @throws RuntimeException if the user is not found or the operation fails
     */
    public Map<String, String> adminGetUserAttributes(String username) {
        try (CognitoIdentityProviderClient cognitoClient = buildCognitoClient()) {

            AdminGetUserRequest adminGetUserRequest = AdminGetUserRequest.builder()
                    .userPoolId(userPoolId)
                    .username(username)
                    .build();

            AdminGetUserResponse adminGetUserResponse = cognitoClient.adminGetUser(adminGetUserRequest);

            Map<String, String> attributes = new HashMap<>();
            attributes.put("username",   adminGetUserResponse.username());
            attributes.put("userStatus", adminGetUserResponse.userStatusAsString());
            for (AttributeType attr : adminGetUserResponse.userAttributes()) {
                attributes.put(attr.name(), attr.value());
            }
            return attributes;

        } catch (UserNotFoundException e) {
            throw new RuntimeException(
                    "User '" + username + "' not found in Cognito User Pool '" + userPoolId + "'.", e);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to retrieve admin user attributes from Cognito for user '"
                    + username + "': " + e.getMessage(), e);
        }
    }

    /**
     * Validates whether a Cognito access token is still active by attempting to
     * retrieve the associated user profile.
     *
     * <p>cr-java-0090 FIX: Token validation is performed by Cognito — no local token
     * files or session files are read from the file system.</p>
     *
     * @param accessToken the JWT access token to validate
     * @return {@code true} if the token is valid and the user exists; {@code false} otherwise
     */
    public boolean validateAccessToken(String accessToken) {
        try {
            getUserAttributes(accessToken);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Builds and returns a {@link CognitoIdentityProviderClient} configured for the
     * injected AWS region. Credentials are resolved automatically via the AWS Default
     * Credential Provider Chain (IAM role, environment variables, instance profile, etc.)
     * — no credentials are read from local files.
     *
     * @return a configured {@link CognitoIdentityProviderClient} instance
     */
    private CognitoIdentityProviderClient buildCognitoClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
