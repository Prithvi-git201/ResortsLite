package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.core.sync.RequestBody;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // BLOCKER-2, BLOCKER-3 FIXED: Replaced absolute file paths with S3 configuration
    @Value("${aws.s3.bucket.reports:resort-reports-bucket}")
    private String reportsBucket;

    @Value("${aws.s3.bucket.backups:resort-backups-bucket}")
    private String backupsBucket;

    // BLOCKER-11 FIXED: Externalized port configuration using environment variable
    @Value("${server.port:8080}")
    private int serverPort;

    @Autowired
    private S3Client s3Client;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = "monthly-reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // BLOCKER-2, BLOCKER-3 FIXED: Writing to S3 instead of local filesystem
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(reportsBucket)
                .key(s3Key)
                .contentType("text/csv")
                .build();

            s3Client.putObject(putRequest, RequestBody.fromString(csvContent.toString()));

            result.put("status", "generated");
            result.put("bucket", reportsBucket);
            result.put("key", s3Key);
            result.put("serverPort", serverPort); // BLOCKER-11 FIXED: Using externalized port

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // Using environment variable for report service URL
        String reportServiceUrl = System.getenv().getOrDefault("REPORT_SERVICE_URL",
            "https://reports.resorts-internal.com:8080");
        return reportServiceUrl + "/download/" + reportName;
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        // BLOCKER-2, BLOCKER-3 FIXED: Using S3 bucket names instead of absolute paths
        info.put("reportsBucket", reportsBucket);
        info.put("backupsBucket", backupsBucket);
        info.put("serverPort", serverPort); // BLOCKER-11 FIXED: Using externalized port
        info.put("generatedAt", timestamp);
        return info;
    }
}
