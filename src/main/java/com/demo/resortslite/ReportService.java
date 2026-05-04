package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    @Autowired
    private S3StorageService s3StorageService;

    // FIXED: blocker-2, blocker-3 (cz-java-0057) - Replaced absolute file paths with S3 storage
    // Hardcoded absolute paths removed - now using S3 for file storage
    // private static final String REPORT_BASE_PATH = "/var/legacy/reports/"; // REMOVED
    // private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\"; // REMOVED

    // FIXED: blocker-11 (cz-java-0061) - Externalized port configuration
    // Hardcoded port replaced with environment variable configuration
    @Value("${server.port:8080}")
    private int serverPort;

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // FIXED: blocker-2 (cz-java-0057) - Using S3 key instead of file path
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            outputStream.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n".getBytes());
            outputStream.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n".getBytes());
            outputStream.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n".getBytes());

            // FIXED: blocker-2 (cz-java-0057) - Upload to S3 instead of local file system
            String uploadedKey = s3StorageService.uploadFile(s3Key, outputStream.toByteArray());
            String s3Uri = s3StorageService.getS3Uri(uploadedKey);

            result.put("status", "generated");
            result.put("path", s3Uri);
            result.put("s3Key", uploadedKey);
            // FIXED: blocker-11 (cz-java-0061) - Using externalized port configuration
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    // VIOLATION [Code Sustainability / Medium]: No JavaDoc or method documentation.
    // Missing documentation is flagged across all public methods in the codebase.
    // This increases onboarding time and transformation risk for automated tools.
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    public Map<String, Object> getSystemInfo() { // doc-missing-001
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        // FIXED: blocker-2, blocker-3 (cz-java-0057) - Removed hardcoded paths
        info.put("reportStorage", "Amazon S3");
        info.put("backupStorage", "Amazon S3");
        // FIXED: blocker-11 (cz-java-0061) - Using externalized port configuration
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
