package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    @Autowired
    private S3Client s3Client;

    // FIXED cr-java-0061, cr-java-0062, cr-java-0063: Replaced hard-coded file paths with Amazon S3
    // All file operations now use S3 for durable, scalable cloud storage
    @Value("${aws.s3.reports-bucket:resorts-reports-bucket}")
    private String reportsBucketName;

    @Value("${aws.s3.backups-bucket:resorts-backups-bucket}")
    private String backupsBucketName;

    // FIXED cr-java-0077: Replaced hard-coded port with environment variable
    // Port is now dynamically assigned by container orchestration (ECS/EKS)
    @Value("${server.port:8080}")
    private int serverPort;

    // FIXED cr-java-0071: Externalized report download URL to Parameter Store
    @Value("${app.reports.base-url:https://reports.resorts-internal.com}")
    private String reportsBaseUrl;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // FIXED cr-java-0062, cr-java-0063: Replaced local file write with S3 upload
            // Generate report content in memory
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            outputStream.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n".getBytes());
            outputStream.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n".getBytes());
            outputStream.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n".getBytes());

            // Upload to S3 instead of writing to local file system
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(reportsBucketName)
                    .key("monthly-reports/" + fileName)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(outputStream.toByteArray()));

            result.put("status", "generated");
            result.put("bucket", reportsBucketName);
            result.put("key", "monthly-reports/" + fileName);
            result.put("s3Uri", "s3://" + reportsBucketName + "/monthly-reports/" + fileName);
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a secure HTTPS URL for report download.
     * FIXED cr-java-0071: Uses externalized base URL from Parameter Store
     * 
     * @param reportName The name of the report file
     * @return HTTPS URL for downloading the report
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIXED cr-java-0071: Using externalized HTTPS URL from Parameter Store
        return reportsBaseUrl + "/download/" + reportName;
    }

    /**
     * Retrieves system configuration information.
     * FIXED cr-java-0111: Uses java.time API with UTC standardization
     * 
     * @return Map containing system configuration details
     */
    public Map<String, Object> getSystemInfo() {
        // FIXED cr-java-0111: Replaced java.util.Date with java.time API
        // All timestamps now use UTC to avoid timezone inconsistencies in distributed environments
        Instant now = Instant.now();
        ZonedDateTime utcTime = now.atZone(ZoneId.of("UTC"));
        String timestamp = utcTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> info = new HashMap<>();
        info.put("reportsBucket", reportsBucketName);
        info.put("backupsBucket", backupsBucketName);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("generatedAtEpoch", now.toEpochMilli());
        info.put("timezone", "UTC");
        return info;
    }

    /**
     * Uploads a backup file to S3.
     * FIXED cr-java-0061, cr-java-0062, cr-java-0063: Replaced local file operations with S3
     * 
     * @param backupName The name of the backup file
     * @param content The backup content as byte array
     * @return Map containing upload status and S3 location
     */
    public Map<String, Object> uploadBackup(String backupName, byte[] content) {
        Map<String, Object> result = new HashMap<>();

        try {
            // FIXED cr-java-0061: Using S3 instead of hardcoded Windows path
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(backupsBucketName)
                    .key("nightly/" + backupName)
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(content));

            result.put("status", "uploaded");
            result.put("bucket", backupsBucketName);
            result.put("key", "nightly/" + backupName);
            result.put("s3Uri", "s3://" + backupsBucketName + "/nightly/" + backupName);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }
}
