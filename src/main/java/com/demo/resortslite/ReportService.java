package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Updated: replaced legacy java.util.Date / java.text.SimpleDateFormat with java.time API
// for Java 21 compatibility (JAVA8_TO_21_DATE_TIME_CHANGES)
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // NOTE: Hardcoded absolute path — not suitable for containerised deployments.
    // Consider using cloud object storage (e.g., S3 / Azure Blob) or an environment variable.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/";

    // NOTE: Windows-style absolute path will fail on Linux-based containers.
    // Must be replaced with a configurable, OS-agnostic path for cloud deployments.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\";

    // NOTE: Fixed server port hardcoded in application logic.
    // Container orchestration (ECS / EKS) dynamically assigns ports.
    private static final int SERVER_PORT = 8080;

    /**
     * Generates a monthly CSV report for the given month and year.
     *
     * @param month the month (e.g., "03")
     * @param year  the year (e.g., "2024")
     * @return a map containing the generation status and file path
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
     * Builds the download URL for a given report file name.
     * NOTE: Uses plain HTTP — consider enforcing HTTPS for cloud deployments.
     *
     * @param reportName the name of the report file
     * @return the download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    /**
     * Returns system information including report paths and current timestamp.
     * Updated: uses java.time.LocalDateTime / DateTimeFormatter instead of
     * legacy java.util.Date / SimpleDateFormat (JAVA8_TO_21_DATE_TIME_CHANGES).
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Updated: replaced legacy SimpleDateFormat / java.util.Date with java.time API
        // for Java 21 compatibility (JAVA8_TO_21_DATE_TIME_CHANGES)
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
