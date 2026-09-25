package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * AWS S3 configuration — provides a managed S3Client bean used by ReportService
 * to replace hard-coded absolute file path operations with Amazon S3 object storage.
 * (cr-java-0061: Hard-coded File Paths remediation)
 */
@Configuration
public class S3Config {

    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * Creates an AWS SDK v2 S3Client bean.
     * Credentials are resolved automatically via the DefaultCredentialsProvider chain:
     *   1. Environment variables (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY)
     *   2. Java system properties
     *   3. AWS credentials file (~/.aws/credentials)
     *   4. ECS task role / EC2 instance profile (recommended for cloud deployments)
     *
     * @return configured S3Client instance
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }
}
