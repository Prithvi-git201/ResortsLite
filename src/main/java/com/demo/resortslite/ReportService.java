package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    private final S3Client s3Client;

    // FIXED cr-java-0061, cr-java-0063: Replaced hard-coded file paths with Amazon S3 configuration
    // S3 bucket name is externalized to AWS Systems Manager Parameter Store
    @Value("${aws.s3.reports.bucket:resorts-reports-bucket}")
    private String reportsBucketName;

    // FIXED cr-java-0077: Replaced hard-coded port with environment variable configuration
    @Value("${server.port:8080}")
    private int serverPort;

    // FIXED cr-java-0071: Externalized environment URL using AWS Systems Manager Parameter Store
    @Value("${app.reports.base.url:https://reports.resorts-internal.com}")
    private String reportsBaseUrl;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // FIXED cr-java-0062, cr-java-0063: Replaced local file write operations with Amazon S3
            // Data is now written to S3 for durable, scalable, and cloud-native storage
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            outputStream.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n".getBytes());
            outputStream.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n".getBytes());
            outputStream.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n".getBytes());

            byte[] reportData = outputStream.toByteArray();

            // Upload to S3
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(reportsBucketName)
                    .key("reports/" + fileName)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putObjectRequest, RequestBody.fromBytes(reportData));

            result.put("status", "generated");
            result.put("s3Bucket", reportsBucketName);
            result.put("s3Key", "reports/" + fileName);
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the specified report.
     * FIXED cr-java-0071: Uses externalized base URL from AWS Parameter Store
     * 
     * @param reportName the name of the report file
     * @return the complete download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIXED cr-java-0071: Using externalized configuration with HTTPS
        return reportsBaseUrl + ":" + serverPort + "/download/" + reportName;
    }

    /**
     * Retrieves system configuration information.
     * FIXED cr-java-0111: Uses java.time API with UTC standardization
     * 
     * @return map containing system configuration details
     */
    public Map<String, Object> getSystemInfo() {
        // FIXED cr-java-0111: Replaced java.util.Date with java.time API and standardized on UTC
        // This eliminates timezone inconsistencies across distributed cloud environments
        String timestamp = Instant.now()
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        Map<String, Object> info = new HashMap<>();
        info.put("reportsBucket", reportsBucketName);
        info.put("serverPort", serverPort);
        info.put("reportsBaseUrl", reportsBaseUrl);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }
}
