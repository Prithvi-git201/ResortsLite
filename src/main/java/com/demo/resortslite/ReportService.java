package com.demo.resortslite;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // FIXED cr-java-0061, cr-java-0062, cr-java-0063: Replaced hardcoded file paths with Google Cloud Storage
    // All file operations now use GCS for durable, scalable cloud-native storage
    @Value("${gcp.storage.bucket-name}")
    private String bucketName;

    @Value("${gcp.storage.project-id:}")
    private String projectId;

    // FIXED cr-java-0077: Replaced hardcoded port with environment-based configuration
    @Value("${server.port}")
    private String serverPort;

    private Storage storage;

    public ReportService() {
        // Initialize Google Cloud Storage client
        this.storage = StorageOptions.getDefaultInstance().getService();
    }

    /**
     * FIXED cr-java-0061, cr-java-0062, cr-java-0063: Generate monthly report using Google Cloud Storage
     * Replaces local file system operations with cloud-native GCS operations for data persistence
     * across container restarts and scaling events.
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        
        Map<String, Object> result = new HashMap<>();

        try {
            // Create CSV content in memory
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // FIXED cr-java-0061, cr-java-0062, cr-java-0063: Upload to Google Cloud Storage instead of local filesystem
            BlobId blobId = BlobId.of(bucketName, "reports/" + fileName);
            BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
                    .setContentType("text/csv")
                    .build();
            
            byte[] content = csvContent.toString().getBytes(StandardCharsets.UTF_8);
            storage.create(blobInfo, content);

            result.put("status", "generated");
            result.put("bucket", bucketName);
            result.put("path", "reports/" + fileName);
            result.put("storage", "Google Cloud Storage");
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * FIXED cr-java-0071: Build report download URL using externalized configuration
     * URLs are now constructed from environment variables instead of hardcoded values
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIXED cr-java-0071: Use environment variable for base URL
        // In production, this would use Cloud Storage signed URLs or Cloud CDN
        String baseUrl = System.getenv("REPORT_BASE_URL");
        if (baseUrl == null || baseUrl.isEmpty()) {
            // Generate GCS public URL or signed URL
            return String.format("https://storage.googleapis.com/%s/reports/%s", bucketName, reportName);
        }
        return baseUrl + "/download/" + reportName;
    }

    /**
     * FIXED cr-java-0111: Replace local time dependencies with UTC standardization
     * All timestamps now use UTC to ensure consistency across distributed cloud environments
     */
    public Map<String, Object> getSystemInfo() {
        // FIXED cr-java-0111: Use UTC timezone instead of server-local time
        String timestamp = DateTimeFormatter.ISO_INSTANT
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        
        Map<String, Object> info = new HashMap<>();
        info.put("storage", "Google Cloud Storage");
        info.put("bucketName", bucketName);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }

    /**
     * FIXED cr-java-0061, cr-java-0063: Read report from Google Cloud Storage
     * Replaces java.io.File operations with GCS client library
     */
    public byte[] downloadReport(String fileName) throws IOException {
        BlobId blobId = BlobId.of(bucketName, "reports/" + fileName);
        return storage.readAllBytes(blobId);
    }

    /**
     * FIXED cr-java-0062: Delete report from Google Cloud Storage
     * Ensures data operations are performed on durable cloud storage
     */
    public boolean deleteReport(String fileName) {
        BlobId blobId = BlobId.of(bucketName, "reports/" + fileName);
        return storage.delete(blobId);
    }
}
