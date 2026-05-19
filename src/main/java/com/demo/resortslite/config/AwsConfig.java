package com.demo.resortslite.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * AWS Configuration for cloud-native services.
 * Configures S3, Secrets Manager, and Systems Manager clients.
 */
@Configuration
public class AwsConfig {

    @Value("${aws.s3.region:us-east-1}")
    private String awsRegion;

    /**
     * Creates S3 client for Amazon S3 object storage.
     * FIXED cr-java-0061, cr-java-0062, cr-java-0063: Replaces local file system operations
     * 
     * @return configured S3Client instance
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * Creates Secrets Manager client for secure credential storage.
     * FIXED cr-java-0069, cr-java-0090: Replaces hard-coded credentials and file-based auth
     * 
     * @return configured SecretsManagerClient instance
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    /**
     * Creates Systems Manager client for parameter store access.
     * FIXED cr-java-0071: Enables externalized configuration management
     * 
     * @return configured SsmClient instance
     */
    @Bean
    public SsmClient ssmClient() {
        return SsmClient.builder()
                .region(Region.of(awsRegion))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
