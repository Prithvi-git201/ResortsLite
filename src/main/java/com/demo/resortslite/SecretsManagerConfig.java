package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Spring configuration for AWS Secrets Manager client.
 *
 * cr-java-0069: Provides a centrally managed SecretsManagerClient bean used by
 * BookingService (and any other service) to retrieve database credentials at
 * runtime from AWS Secrets Manager, eliminating hard-coded credentials from
 * source code and enabling automatic credential rotation without redeployment.
 *
 * The AWS region is resolved from the environment variable AWS_REGION (with a
 * default of "us-east-1" for local development). In production the SDK will
 * also pick up credentials automatically from the EC2/ECS/Lambda instance role
 * via the default credential provider chain — no access keys need to be
 * embedded in configuration files.
 */
@Configuration
public class SecretsManagerConfig {

    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    /**
     * Creates and exposes a {@link SecretsManagerClient} bean.
     * The client uses the AWS default credential provider chain, which resolves
     * credentials in the following order:
     * <ol>
     *   <li>Environment variables (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY)</li>
     *   <li>Java system properties</li>
     *   <li>AWS credentials file (~/.aws/credentials)</li>
     *   <li>ECS container credentials (when running on ECS/Fargate)</li>
     *   <li>EC2 instance profile / IAM role (when running on EC2)</li>
     * </ol>
     *
     * @return a configured {@link SecretsManagerClient}
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
