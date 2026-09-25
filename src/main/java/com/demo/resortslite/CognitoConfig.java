package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/**
 * Spring configuration for Amazon Cognito Identity Provider client.
 *
 * cr-java-0090 (File-based Authentication): Provides a centrally managed
 * {@link CognitoIdentityProviderClient} bean used by {@link CognitoAuthService}
 * to authenticate users and manage user identities via Amazon Cognito User Pools,
 * replacing local file-based credential storage and MD5-based token generation.
 *
 * <p>The AWS region is resolved from the environment variable AWS_REGION (with a
 * default of "us-east-1" for local development). In production the SDK will pick up
 * credentials automatically from the EC2/ECS/Lambda instance role via the default
 * credential provider chain — no access keys need to be embedded in configuration files.
 *
 * <p>Required IAM permissions for the application's execution role:
 * <ul>
 *   <li>{@code cognito-idp:AdminInitiateAuth} — for user authentication</li>
 *   <li>{@code cognito-idp:AdminGetUser} — for user attribute retrieval</li>
 * </ul>
 */
@Configuration
public class CognitoConfig {

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Creates and exposes a {@link CognitoIdentityProviderClient} bean.
     *
     * <p>The client uses the AWS default credential provider chain, which resolves
     * credentials in the following order:
     * <ol>
     *   <li>Environment variables (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY)</li>
     *   <li>Java system properties</li>
     *   <li>AWS credentials file (~/.aws/credentials)</li>
     *   <li>ECS container credentials (when running on ECS/Fargate)</li>
     *   <li>EC2 instance profile / IAM role (when running on EC2)</li>
     * </ol>
     *
     * @return a configured {@link CognitoIdentityProviderClient}
     */
    @Bean
    public CognitoIdentityProviderClient cognitoIdentityProviderClient() {
        return CognitoIdentityProviderClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
