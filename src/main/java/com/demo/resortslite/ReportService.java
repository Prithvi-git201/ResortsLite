package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    @Autowired
    private S3FileService s3FileService;

    // FIXED: blocker-2 (cz-java-0057) - Replaced absolute file path with S3 configuration
    // Reports are now stored in S3 bucket configured via environment variables

    // FIXED: blocker-3 (cz-java-0057) - Replaced Windows-style absolute path with S3 storage
    // Backups are now stored in S3 bucket configured via environment variables

    // FIXED: blocker-11 (cz-java-0061) - Externalized port configuration to environment variable
    private int getServerPort() {
        String portEnv = System.getenv().getOrDefault("SERVER_PORT", "8080");
        return Integer.parseInt(portEnv);
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // FIXED: blocker-2 (cz-java-0057) - Using S3 instead of local file system
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Generate report content
            StringBuilder reportContent = new StringBuilder();
            reportContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            reportContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            reportContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // FIXED: blocker-2 (cz-java-0057) - Upload to S3 instead of local file system
            s3FileService.uploadFile(s3Key, reportContent.toString());

            result.put("status", "generated");
            result.put("path", s3FileService.getS3Uri(s3Key));
            result.put("serverPort", getServerPort());

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Build report download URL
     * @param reportName Report file name
     * @return Download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIXED: blocker-11 (cz-java-0061) - Using environment variable for port
        int serverPort = getServerPort();
        // Using HTTPS and environment-based hostname
        String hostname = System.getenv().getOrDefault("REPORT_SERVICE_HOST", "reports.resorts-internal.com");
        return String.format("https://%s:%d/download/%s", hostname, serverPort, reportName);
    }

    /**
     * Get system information
     * @return System info map
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        // FIXED: blocker-2, blocker-3 (cz-java-0057) - Using S3 bucket info instead of local paths
        info.put("reportStorage", "S3 Bucket: " + System.getenv().getOrDefault("S3_BUCKET_NAME", "resorts-lite-files"));
        info.put("backupStorage", "S3 Bucket: " + System.getenv().getOrDefault("S3_BUCKET_NAME", "resorts-lite-files"));
        // FIXED: blocker-11 (cz-java-0061) - Using environment variable for port
        info.put("serverPort", getServerPort());
        info.put("generatedAt", timestamp);
        return info;
    }
}
