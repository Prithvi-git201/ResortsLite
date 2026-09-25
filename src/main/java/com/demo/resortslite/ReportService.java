package com.demo.resortslite;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
// Migrated from legacy java.util.Date / java.text.SimpleDateFormat to java.time API
// for Java 17 compatibility (JAVA8_TO_21_DATE_TIME_CHANGES)
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Service responsible for generating and managing resort reports.
 *
 * <p>NOTE: Several hardcoded paths and ports in this service should be externalised
 * to environment variables or cloud configuration (e.g., AWS Parameter Store / S3)
 * before deploying to a containerised or cloud-native environment.</p>
 */
@Service
public class ReportService {

    // NOTE: Hardcoded absolute path — /var/legacy/reports does not exist in a Docker
    // container image. For cloud deployments, use volume mounts, S3, or an env variable.
    private static final String REPORT_BASE_PATH = "/var/legacy/reports/";

    // NOTE: Windows-style absolute path will fail on Linux-based containers.
    // Externalise to an environment variable for cross-platform compatibility.
    private static final String BACKUP_PATH = "C:\\ResortBackups\\nightly\\";

    // NOTE: Fixed server port hardcoded in application logic.
    // Container orchestration (ECS/EKS) dynamically assigns ports.
    // Use server.port property or PORT environment variable instead.
    private static final int SERVER_PORT = 8080;

    /**
     * Generates a monthly booking report as a CSV file.
     *
     * @param month the month for which the report is generated (e.g., "March")
     * @param year  the year for which the report is generated (e.g., "2024")
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

            FileWriter writer = new FileWriter(fullPath);
            writer.write("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            writer.write("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            writer.write("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");
            writer.close();

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
     * Builds the download URL for a given report file.
     *
     * <p>NOTE: Plain HTTP URL hardcoded for report download.
     * Cloud security standards enforce HTTPS. Externalise the base URL via environment variable.</p>
     *
     * @param reportName the name of the report file
     * @return the full download URL string
     */
    public String buildReportDownloadUrl(String reportName) {
        return "http://reports.resorts-internal.com:8080/download/" + reportName;
    }

    /**
     * Returns system information including report paths and current timestamp.
     *
     * <p>Uses {@link LocalDateTime} (java.time API) instead of the legacy
     * {@code java.util.Date} / {@code SimpleDateFormat} for Java 17 compatibility.</p>
     *
     * @return a map containing system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // Updated from legacy java.util.Date + SimpleDateFormat to java.time.LocalDateTime
        // for Java 17 compatibility (JAVA8_TO_21_DATE_TIME_CHANGES)
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
