package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Service for handling file operations using Amazon S3.
 * Replaces local file system operations for container portability.
 */
@Service
public class S3FileService {

    @Value("${aws.s3.bucket-name}")
    private String bucketName;

    @Value("${aws.s3.region}")
    private String region;

    private S3Client s3Client;

    @PostConstruct
    public void init() {
        this.s3Client = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @PreDestroy
    public void cleanup() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

    /**
     * Upload content to S3
     * @param key S3 object key (file path)
     * @param content Content to upload
     */
    public void uploadFile(String key, String content) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        
        s3Client.putObject(putObjectRequest, RequestBody.fromString(content));
    }

    /**
     * Upload bytes to S3
     * @param key S3 object key (file path)
     * @param bytes Byte array to upload
     */
    public void uploadFile(String key, byte[] bytes) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        
        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(bytes));
    }

    /**
     * Download file from S3
     * @param key S3 object key (file path)
     * @return File content as byte array
     */
    public byte[] downloadFile(String key) throws IOException {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .build();
        
        try (InputStream inputStream = s3Client.getObject(getObjectRequest);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            return outputStream.toByteArray();
        }
    }

    /**
     * Generate S3 object key from file path
     * @param filePath Original file path
     * @return S3 object key
     */
    public String generateS3Key(String filePath) {
        // Remove leading slashes and backslashes, normalize path
        String normalized = filePath.replaceAll("^[/\\\\]+", "")
                                    .replace("\\", "/");
        return normalized;
    }

    /**
     * Get S3 URI for a file
     * @param key S3 object key
     * @return S3 URI
     */
    public String getS3Uri(String key) {
        return String.format("s3://%s/%s", bucketName, key);
    }
}
