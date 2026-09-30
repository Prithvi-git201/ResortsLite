package com.demo.resortslite;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Updated from java.util.Date / java.text.SimpleDateFormat to java.time API
// (JAVA8_TO_21_DATE_TIME_CHANGES) — java.util.Date and SimpleDateFormat are legacy,
// not thread-safe, and discouraged in Java 21. java.time.* is the modern replacement.
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // FIX czr-java-001 [Software Portability / Mandatory]: Replaced hardcoded absolute path
    // /var/legacy/reports with an externalisable property injected via @Value.
    // Default falls back to /tmp/reports/ which is available in all Linux containers.
    // Override via REPORT_BASE_PATH environment variable or app.report.base-path property.
    @Value("${app.report.base-path:/tmp/reports/}")
    private String reportBasePath;

    // FIX czr-java-001 [Software Portability / Mandatory]: Removed hardcoded Windows-style
    // absolute path C:\ResortBackups\nightly\. Backup path is now externalised to an
    // environment variable / application property to support Linux containers and cloud hosts.
    @Value("${app.backup.path:/tmp/backups/}")
    private String backupPath;

    // FIX czr-port-001 [Software Portability / High]: Removed hardcoded SERVER_PORT constant.
    // Port is now read from the Spring environment (server.port / SERVER_PORT env var),
    // allowing container orchestration (ECS / EKS) to assign ports dynamically.
    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * Generates a monthly CSV report for the given month and year.
     * Uses java.time.LocalDateTime (Java 8+ / Java 21 compatible) instead of
     * the legacy java.util.Date API.
     *
     * @param month the month (e.g. "03")
     * @param year  the year  (e.g. "2024")
     * @return a map containing the generation status and file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // FIX czr-java-001: Use injected reportBasePath instead of hardcoded constant
        String fullPath = reportBasePath + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // FIX czr-java-001: Use injected reportBasePath instead of hardcoded constant
            File reportDir = new File(reportBasePath);
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
            // FIX czr-port-001: Use injected serverPort instead of hardcoded constant
            result.put("serverPort", serverPort);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the given report name.
     * FIX cr-java-0088 [Cloud Compatibility / Mandatory]: Replaced plain HTTP URL with
     * HTTPS to comply with cloud security standards (AWS ALB / WAF enforce HTTPS).
     * Host is externalised via the app.report.download.host property / environment variable.
     *
     * @param reportName the name of the report file
     * @return the download URL string
     */
    @Value("${app.report.download.host:reports.resorts-internal.com}")
    private String reportDownloadHost;

    public String buildReportDownloadUrl(String reportName) {
        // FIX cr-java-0088: Use HTTPS and externalised host instead of hardcoded HTTP URL
        return "https://" + reportDownloadHost + ":" + serverPort + "/download/" + reportName;
    }

    /**
     * Returns system information including report paths, backup path, server port,
     * and the current timestamp formatted using the modern java.time API.
     * Updated from legacy java.util.Date + SimpleDateFormat to java.time.LocalDateTime
     * + java.time.format.DateTimeFormatter (JAVA8_TO_21_DATE_TIME_CHANGES).
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Updated from legacy java.util.Date + SimpleDateFormat to java.time API
        // (JAVA8_TO_21_DATE_TIME_CHANGES)
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        // FIX czr-java-001: Use injected paths instead of hardcoded constants
        info.put("reportPath", reportBasePath);
        info.put("backupPath", backupPath);
        // FIX czr-port-001: Use injected port instead of hardcoded constant
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
