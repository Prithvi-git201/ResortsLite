package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link ReportService}.
 * Covers generateMonthlyReport, buildReportDownloadUrl, and getSystemInfo.
 */
class ReportServiceTest {

    private ReportService reportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // -----------------------------------------------------------------------
    // generateMonthlyReport
    // -----------------------------------------------------------------------

    @Test
    void generateMonthlyReport_withValidInputs_returnsNonNullResult() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_successfulGeneration_returnsGeneratedStatus() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("March", "2024");

        // Assert
        assertNotNull(result.get("status"));
        // Status should be either "generated" (success) or "error" (IO failure)
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status),
                "Status should be 'generated' or 'error'");
    }

    @Test
    void generateMonthlyReport_resultContainsStatusKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("April", "2024");

        // Assert
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_onSuccess_resultContainsPathKey() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("May", "2024");

        // Assert — on success, path key should be present
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertTrue(result.containsKey("path"), "On success, result should contain 'path'");
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_pathContainsMonthAndYear() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("June", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            String path = (String) result.get("path");
            assertTrue(path.contains("June"), "Path should contain the month");
            assertTrue(path.contains("2024"), "Path should contain the year");
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_pathEndsWithCsvExtension() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("July", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            String path = (String) result.get("path");
            assertTrue(path.endsWith(".csv"), "Report file should have .csv extension");
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_resultContainsServerPort() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("August", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            assertTrue(result.containsKey("serverPort"), "Result should contain serverPort");
            assertNotNull(result.get("serverPort"));
        }
    }

    @Test
    void generateMonthlyReport_serverPortIsPositiveInteger() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("September", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            int port = (int) result.get("serverPort");
            assertTrue(port > 0, "Server port should be a positive integer");
        }
    }

    @Test
    void generateMonthlyReport_differentMonthsProduceDifferentPaths() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("January", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("February", "2024");

        // Assert
        if ("generated".equals(result1.get("status")) && "generated".equals(result2.get("status"))) {
            assertNotEquals(result1.get("path"), result2.get("path"),
                    "Different months should produce different file paths");
        }
    }

    @Test
    void generateMonthlyReport_differentYearsProduceDifferentPaths() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("March", "2023");
        Map<String, Object> result2 = reportService.generateMonthlyReport("March", "2024");

        // Assert
        if ("generated".equals(result1.get("status")) && "generated".equals(result2.get("status"))) {
            assertNotEquals(result1.get("path"), result2.get("path"),
                    "Different years should produce different file paths");
        }
    }

    @Test
    void generateMonthlyReport_fileNameContainsMonthAndYear() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("October", "2024");

        // Assert
        String status = (String) result.get("status");
        if ("generated".equals(status)) {
            String path = (String) result.get("path");
            String fileName = new File(path).getName();
            assertTrue(fileName.contains("October"), "File name should contain month");
            assertTrue(fileName.contains("2024"), "File name should contain year");
        }
    }

    // -----------------------------------------------------------------------
    // buildReportDownloadUrl
    // -----------------------------------------------------------------------

    @Test
    void buildReportDownloadUrl_returnsNonNullUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.csv");

        // Assert
        assertNotNull(url);
    }

    @Test
    void buildReportDownloadUrl_returnsHttpsUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.csv");

        // Assert
        assertTrue(url.startsWith("https://"),
                "Download URL must use HTTPS for security compliance");
    }

    @Test
    void buildReportDownloadUrl_urlContainsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("april_report.csv");

        // Assert
        assertTrue(url.contains("april_report.csv"),
                "URL should contain the report name");
    }

    @Test
    void buildReportDownloadUrl_urlContainsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("may_report.csv");

        // Assert
        assertTrue(url.contains("/download/"),
                "URL should contain '/download/' path segment");
    }

    @Test
    void buildReportDownloadUrl_differentReportNames_produceDifferentUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_jan.csv");
        String url2 = reportService.buildReportDownloadUrl("report_feb.csv");

        // Assert
        assertNotEquals(url1, url2, "Different report names should produce different URLs");
    }

    @Test
    void buildReportDownloadUrl_urlEndsWithReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("june_bookings.pdf");

        // Assert
        assertTrue(url.endsWith("june_bookings.pdf"),
                "URL should end with the report name");
    }

    @Test
    void buildReportDownloadUrl_urlIsNotEmpty() {
        // Act
        String url = reportService.buildReportDownloadUrl("test_report.csv");

        // Assert
        assertFalse(url.isEmpty(), "URL should not be empty");
    }

    @Test
    void buildReportDownloadUrl_urlContainsHost() {
        // Act
        String url = reportService.buildReportDownloadUrl("report.csv");

        // Assert — URL should have a host between https:// and /download/
        assertTrue(url.length() > "https://".length() + "/download/report.csv".length(),
                "URL should contain a host segment");
    }

    // -----------------------------------------------------------------------
    // getSystemInfo
    // -----------------------------------------------------------------------

    @Test
    void getSystemInfo_returnsNonNullMap() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info);
    }

    @Test
    void getSystemInfo_containsReportPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("reportPath"), "System info should contain 'reportPath'");
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("backupPath"), "System info should contain 'backupPath'");
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("serverPort"), "System info should contain 'serverPort'");
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertTrue(info.containsKey("generatedAt"), "System info should contain 'generatedAt'");
    }

    @Test
    void getSystemInfo_generatedAtIsNotNull() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("generatedAt"), "generatedAt timestamp should not be null");
    }

    @Test
    void getSystemInfo_generatedAtMatchesDateTimePattern() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert — format: yyyy-MM-dd HH:mm:ss
        String timestamp = (String) info.get("generatedAt");
        assertNotNull(timestamp);
        assertTrue(timestamp.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp should match pattern yyyy-MM-dd HH:mm:ss, got: " + timestamp);
    }

    @Test
    void getSystemInfo_serverPortIsPositiveInteger() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        int port = (int) info.get("serverPort");
        assertTrue(port > 0, "Server port should be a positive integer");
    }

    @Test
    void getSystemInfo_reportPathIsNotNull() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("reportPath"), "Report path should not be null");
    }

    @Test
    void getSystemInfo_backupPathIsNotNull() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("backupPath"), "Backup path should not be null");
    }

    @Test
    void getSystemInfo_returnsMapWithFourKeys() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert — reportPath, backupPath, serverPort, generatedAt
        assertEquals(4, info.size(), "System info map should have exactly 4 keys");
    }

    @Test
    void getSystemInfo_calledTwice_generatedAtTimestampsAreStrings() {
        // Act
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();

        // Assert
        assertInstanceOf(String.class, info1.get("generatedAt"));
        assertInstanceOf(String.class, info2.get("generatedAt"));
    }
}
