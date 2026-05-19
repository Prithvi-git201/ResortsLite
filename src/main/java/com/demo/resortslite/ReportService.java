package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // VIOLATION czr-java-001 [Software Portability / Mandatory]: Hardcoded absolute path.
    // /var/legacy/reports does not exist in a Docker container image. Breaks containerisation.
    // Must use volume mounts, cloud object storage (S3 / Azure Blob), or environment variable.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/"; // czr-java-001

    // VIOLATION czr-java-001 [Software Portability / Mandatory]: Windows-style absolute path
    // will fail on any Linux-based container or cloud host. Hard dependency on OS path structure.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\"; // czr-java-001

    // VIOLATION [Software Portability / High]: Fixed server port hardcoded in application logic.
    // Container orchestration (ECS / EKS) dynamically assigns ports. Hardcoded ports prevent
    // dynamic port binding required for modern container deployment and service discovery.
    private static final int SERVER_PORT = 8080; // czr-port-001

    // FIXED: Using modern DateTimeFormatter instead of SimpleDateFormat
    private static final DateTimeFormatter DATE_TIME_FORMATTER = 
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = REPORT_BASE_PATH + fileName; // czr-java-001

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(REPORT_BASE_PATH); // czr-java-001
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            FileWriter writer = new FileWriter(fullPath);
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.close();

            result.put("status", "generated");
            result.put("path", fullPath);
            result.put("serverPort", SERVER_PORT); // czr-port-001

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the specified report.
     * FIXED: Added JavaDoc documentation for better code maintainability.
     * 
     * @param reportName the name of the report file
     * @return the complete download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // VIOLATION cr-java-0088 [Cloud Compatibility / Mandatory]: Plain HTTP URL
        // hardcoded for report download. Cloud security standards enforce HTTPS.
        return "http://reports.resorts-internal.com:8080/download/" + reportName; // cr-java-0088
    }

    /**
     * Retrieves system information including paths and configuration.
     * FIXED: Added JavaDoc documentation for better code maintainability.
     * 
     * @return a map containing system information
     */
    public Map<String, Object> getSystemInfo() {
        // FIXED: Using modern LocalDateTime and DateTimeFormatter instead of Date and SimpleDateFormat
        String timestamp = LocalDateTime.now().format(DATE_TIME_FORMATTER);
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);  // czr-java-001
        info.put("backupPath", BACKUP_PATH);        // czr-java-001
        info.put("serverPort", SERVER_PORT);        // czr-port-001
        info.put("generatedAt", timestamp);
        return info;
    }
}
