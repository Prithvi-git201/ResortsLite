package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReportService}.
 * Uses a JUnit 5 {@code @TempDir} so file-system operations are isolated and
 * cleaned up automatically after each test.
 */
class ReportServiceTest {

    private ReportService reportService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
        // Inject @Value fields that Spring would normally populate
        ReflectionTestUtils.setField(reportService, "reportBasePath",
                tempDir.toString() + "/reports/");
        ReflectionTestUtils.setField(reportService, "backupPath",
                tempDir.toString() + "/backups/");
        ReflectionTestUtils.setField(reportService, "serverPort", 8080);
        ReflectionTestUtils.setField(reportService, "reportDownloadHost",
                "reports.resorts-internal.com");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateMonthlyReport
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateMonthlyReport_validInput_returnsGeneratedStatus() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        assertNotNull(result);
        assertEquals("generated", result.get("status"));
    }

    @Test
    void generateMonthlyReport_createsFileOnDisk() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");

        // Assert
        String path = (String) result.get("path");
        assertNotNull(path);
        assertTrue(new File(path).exists(), "Report file should exist on disk");
    }

    @Test
    void generateMonthlyReport_fileNameContainsMonthAndYear() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("07", "2025");

        // Assert
        String path = (String) result.get("path");
        assertTrue(path.contains("07"), "Path should contain month");
        assertTrue(path.contains("2025"), "Path should contain year");
    }

    @Test
    void generateMonthlyReport_includesServerPort() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("01", "2024");

        // Assert
        assertEquals(8080, result.get("serverPort"));
    }

    @Test
    void generateMonthlyReport_createsDirectoryIfAbsent() {
        // Arrange — use a sub-directory that does not yet exist
        String newDir = tempDir.toString() + "/new-reports/";
        ReflectionTestUtils.setField(reportService, "reportBasePath", newDir);

        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("05", "2024");

        // Assert
        assertEquals("generated", result.get("status"));
        assertTrue(new File(newDir).exists(), "Directory should have been created");
    }

    @Test
    void generateMonthlyReport_differentMonths_produceDifferentFiles() {
        // Act
        Map<String, Object> result1 = reportService.generateMonthlyReport("01", "2024");
        Map<String, Object> result2 = reportService.generateMonthlyReport("02", "2024");

        // Assert
        assertNotEquals(result1.get("path"), result2.get("path"));
    }

    @Test
    void generateMonthlyReport_pathUsesConfiguredBasePath() {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("04", "2024");

        // Assert
        String path = (String) result.get("path");
        assertTrue(path.startsWith(tempDir.toString()),
                "Path should start with the configured base path");
    }

    @Test
    void generateMonthlyReport_csvFileContainsHeader() throws Exception {
        // Act
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");

        // Assert
        String path = (String) result.get("path");
        String content = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
        assertTrue(content.contains("BookingID"), "CSV should contain header row");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildReportDownloadUrl
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_returnsHttpsUrl() {
        // Act
        String url = reportService.buildReportDownloadUrl("march_report.pdf");

        // Assert
        assertNotNull(url);
        assertTrue(url.startsWith("https://"), "URL must use HTTPS");
    }

    @Test
    void buildReportDownloadUrl_containsReportName() {
        // Act
        String url = reportService.buildReportDownloadUrl("annual_2024.pdf");

        // Assert
        assertTrue(url.contains("annual_2024.pdf"));
    }

    @Test
    void buildReportDownloadUrl_containsConfiguredHost() {
        // Act
        String url = reportService.buildReportDownloadUrl("test.pdf");

        // Assert
        assertTrue(url.contains("reports.resorts-internal.com"));
    }

    @Test
    void buildReportDownloadUrl_containsServerPort() {
        // Act
        String url = reportService.buildReportDownloadUrl("test.pdf");

        // Assert
        assertTrue(url.contains("8080"), "URL should include the server port");
    }

    @Test
    void buildReportDownloadUrl_containsDownloadPath() {
        // Act
        String url = reportService.buildReportDownloadUrl("q1_report.pdf");

        // Assert
        assertTrue(url.contains("/download/"), "URL should contain /download/ segment");
    }

    @Test
    void buildReportDownloadUrl_differentReportNames_produceDifferentUrls() {
        // Act
        String url1 = reportService.buildReportDownloadUrl("report_a.pdf");
        String url2 = reportService.buildReportDownloadUrl("report_b.pdf");

        // Assert
        assertNotEquals(url1, url2);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getSystemInfo
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getSystemInfo_returnsNonNullMap() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info);
    }

    @Test
    void getSystemInfo_containsReportPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("reportPath"));
    }

    @Test
    void getSystemInfo_containsBackupPath() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("backupPath"));
    }

    @Test
    void getSystemInfo_containsServerPort() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAt() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        assertNotNull(info.get("generatedAt"));
        String ts = (String) info.get("generatedAt");
        // Verify format: yyyy-MM-dd HH:mm:ss
        assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "Timestamp should match yyyy-MM-dd HH:mm:ss format, got: " + ts);
    }

    @Test
    void getSystemInfo_reportPathMatchesInjectedValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String reportPath = (String) info.get("reportPath");
        assertTrue(reportPath.startsWith(tempDir.toString()));
    }

    @Test
    void getSystemInfo_backupPathMatchesInjectedValue() {
        // Act
        Map<String, Object> info = reportService.getSystemInfo();

        // Assert
        String backupPath = (String) info.get("backupPath");
        assertTrue(backupPath.startsWith(tempDir.toString()));
    }
}
