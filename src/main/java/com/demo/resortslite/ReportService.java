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

    // Updated czr-java-001: base path externalised to environment variable; falls back to a
    // portable OS temp directory so the application works inside containers and on any OS.
    // (replaces the original hardcoded absolute path /var/legacy/reports)
    private static final String REPORT_BASE_PATH =
            System.getenv().getOrDefault("REPORT_BASE_PATH",
                    System.getProperty("java.io.tmpdir") + File.separator + "reports" + File.separator);

    // Updated czr-java-001: backup path externalised to environment variable;
    // Windows-style absolute path replaced with a portable, OS-neutral default.
    private static final String BACKUP_PATH =
            System.getenv().getOrDefault("BACKUP_PATH",
                    System.getProperty("java.io.tmpdir") + File.separator + "backups" + File.separator);

    // Updated czr-port-001: server port externalised to environment variable so container
    // orchestrators (ECS / EKS) can assign ports dynamically.
    private static final int SERVER_PORT =
            Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));

    /**
     * Generates a monthly CSV report and writes it to the configured report directory.
     *
     * @param month the month label (e.g. "March")
     * @param year  the four-digit year (e.g. "2024")
     * @return a map containing the generation status and output path
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
     * Builds the HTTPS download URL for the given report name.
     * Updated cr-java-0088: plain HTTP replaced with HTTPS to comply with cloud security standards.
     * Report host is externalised to an environment variable.
     *
     * @param reportName the name of the report file
     * @return the fully-qualified HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        String reportHost = System.getenv().getOrDefault(
                "REPORT_HOST", "reports.resorts-internal.com");
        return "https://" + reportHost + "/download/" + reportName;
    }

    /**
     * Returns basic system information including configured paths and current timestamp.
     * Updated: java.util.Date / SimpleDateFormat replaced with thread-safe java.time API
     * (LocalDateTime + DateTimeFormatter) — compatible with Java 21.
     *
     * @return a map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // Updated: java.util.Date / SimpleDateFormat replaced with thread-safe java.time API.
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
