package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Updated: java.util.Date and java.text.SimpleDateFormat replaced with java.time API
// for Java 25 compatibility — legacy date/time APIs are deprecated in favour of java.time
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // NOTE: Hardcoded absolute path — consider using cloud object storage (S3 / Azure Blob)
    // or environment-variable-driven paths for container-compatible deployments.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/";

    // NOTE: Windows-style absolute path will fail on Linux-based containers.
    // Externalise to environment variable or use cloud object storage.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\";

    // NOTE: Fixed server port — container orchestration (ECS/EKS) dynamically assigns ports.
    // Externalise to environment variable or application configuration.
    private static final int SERVER_PORT = 8080;

    /**
     * Generates a monthly report CSV file for the given month and year.
     *
     * @param month the month for which the report is generated
     * @param year  the year for which the report is generated
     * @return a map containing the report generation status and file path
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String fullPath = REPORT_BASE_PATH + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            File reportDir = new File(REPORT_BASE_PATH);
            if (!reportDir.exists()) {
                reportDir.mkdirs();
            }

            try (FileWriter writer = new FileWriter(fullPath)) {
                writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
                writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
                writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            }

            result.put("status", "generated");
            result.put("path", fullPath);
            result.put("serverPort", SERVER_PORT);

        } catch (IOException e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds the download URL for a given report name.
     *
     * @param reportName the name of the report file
     * @return the full download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        // NOTE: Consider enforcing HTTPS for internal service calls in cloud environments.
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    /**
     * Returns system information including report paths, backup path, server port,
     * and the current timestamp.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Updated: java.util.Date + SimpleDateFormat replaced with java.time.LocalDateTime
        // and DateTimeFormatter for Java 25 compatibility and thread-safety.
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        Map<String, Object> info = new HashMap<>();
        info.put("reportPath", REPORT_BASE_PATH);
        info.put("backupPath", BACKUP_PATH);
        info.put("serverPort", SERVER_PORT);
        info.put("generatedAt", timestamp);
        return info;
    }
}
