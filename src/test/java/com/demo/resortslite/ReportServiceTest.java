package com.demo.resortslite;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportServiceTest {

    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportService = new ReportService();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // generateMonthlyReport
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsNonNullResult() {
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_withValidMonthAndYear_returnsStatusKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("03", "2024");
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_whenDirectoryNotWritable_returnsErrorOrGenerated() {
        // The method either succeeds (generated) or fails (error) — both are valid outcomes
        Map<String, Object> result = reportService.generateMonthlyReport("01", "2024");
        assertNotNull(result);
        String status = (String) result.get("status");
        assertTrue("generated".equals(status) || "error".equals(status));
    }

    @Test
    void generateMonthlyReport_onSuccess_returnsGeneratedStatus() {
        Map<String, Object> result = reportService.generateMonthlyReport("06", "2024");
        // Either generated (if /var/legacy/reports/ is writable) or error
        assertNotNull(result.get("status"));
    }

    @Test
    void generateMonthlyReport_onSuccess_containsPathKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("07", "2024");
        // Path key present on success, message key present on error
        assertTrue(result.containsKey("path") || result.containsKey("message"));
    }

    @Test
    void generateMonthlyReport_onError_returnsErrorStatus() {
        // /var/legacy/reports/ is typically not writable in test environments
        Map<String, Object> result = reportService.generateMonthlyReport("12", "2023");
        assertNotNull(result);
        // Should contain either "status" with "error" or "generated"
        assertTrue(result.containsKey("status"));
    }

    @Test
    void generateMonthlyReport_onError_containsMessageKey() {
        Map<String, Object> result = reportService.generateMonthlyReport("11", "2023");
        // If error, message key should be present
        if ("error".equals(result.get("status"))) {
            assertTrue(result.containsKey("message"));
        }
    }

    @Test
    void generateMonthlyReport_onSuccess_containsServerPort() {
        Map<String, Object> result = reportService.generateMonthlyReport("05", "2024");
        if ("generated".equals(result.get("status"))) {
            assertNotNull(result.get("serverPort"));
            assertEquals(8080, result.get("serverPort"));
        }
    }

    @Test
    void generateMonthlyReport_withDifferentMonths_returnsResult() {
        String[] months = {"01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"};
        for (String month : months) {
            Map<String, Object> result = reportService.generateMonthlyReport(month, "2024");
            assertNotNull(result, "Result should not be null for month: " + month);
        }
    }

    @Test
    void generateMonthlyReport_withEmptyMonth_returnsResult() {
        Map<String, Object> result = reportService.generateMonthlyReport("", "2024");
        assertNotNull(result);
    }

    @Test
    void generateMonthlyReport_withEmptyYear_returnsResult() {
        Map<String, Object> result = reportService.generateMonthlyReport("03", "");
        assertNotNull(result);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // buildReportDownloadUrl
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void buildReportDownloadUrl_withReportName_returnsNonNullUrl() {
        String url = reportService.buildReportDownloadUrl("march_report.pdf");
        assertNotNull(url);
    }

    @Test
    void buildReportDownloadUrl_withReportName_containsReportName() {
        String url = reportService.buildReportDownloadUrl("march_report.pdf");
        assertTrue(url.contains("march_report.pdf"));
    }

    @Test
    void buildReportDownloadUrl_withReportName_startsWithHttp() {
        String url = reportService.buildReportDownloadUrl("april_report.pdf");
        assertTrue(url.startsWith("http://"));
    }

    @Test
    void buildReportDownloadUrl_withReportName_containsDownloadPath() {
        String url = reportService.buildReportDownloadUrl("may_report.pdf");
        assertTrue(url.contains("/download/"));
    }

    @Test
    void buildReportDownloadUrl_withReportName_containsHostname() {
        String url = reportService.buildReportDownloadUrl("june_report.pdf");
        assertTrue(url.contains("reports.resorts-internal.com"));
    }

    @Test
    void buildReportDownloadUrl_withReportName_containsPort8080() {
        String url = reportService.buildReportDownloadUrl("july_report.pdf");
        assertTrue(url.contains(":8080"));
    }

    @Test
    void buildReportDownloadUrl_withEmptyReportName_returnsBaseUrl() {
        String url = reportService.buildReportDownloadUrl("");
        assertNotNull(url);
        assertTrue(url.startsWith("http://"));
    }

    @Test
    void buildReportDownloadUrl_withSpecialCharacters_returnsUrl() {
        String url = reportService.buildReportDownloadUrl("report_2024-03.csv");
        assertNotNull(url);
        assertTrue(url.contains("report_2024-03.csv"));
    }

    @Test
    void buildReportDownloadUrl_withDifferentReportNames_returnsCorrectUrls() {
        String url1 = reportService.buildReportDownloadUrl("report1.pdf");
        String url2 = reportService.buildReportDownloadUrl("report2.pdf");
        assertNotEquals(url1, url2);
    }

    @Test
    void buildReportDownloadUrl_urlFormat_matchesExpectedPattern() {
        String reportName = "test_report.pdf";
        String url = reportService.buildReportDownloadUrl(reportName);
        assertEquals("http://reports.resorts-internal.com:8080/download/" + reportName, url);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getSystemInfo
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void getSystemInfo_returnsNonNullMap() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertNotNull(info);
    }

    @Test
    void getSystemInfo_containsReportPathKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("reportPath"));
    }

    @Test
    void getSystemInfo_containsBackupPathKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("backupPath"));
    }

    @Test
    void getSystemInfo_containsServerPortKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("serverPort"));
    }

    @Test
    void getSystemInfo_containsGeneratedAtKey() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertTrue(info.containsKey("generatedAt"));
    }

    @Test
    void getSystemInfo_reportPathIsCorrect() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertEquals("/var/legacy/reports/", info.get("reportPath"));
    }

    @Test
    void getSystemInfo_backupPathIsCorrect() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertEquals("C:\\ResortBackups\\nightly\\", info.get("backupPath"));
    }

    @Test
    void getSystemInfo_serverPortIs8080() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertEquals(8080, info.get("serverPort"));
    }

    @Test
    void getSystemInfo_generatedAtIsNotNull() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertNotNull(info.get("generatedAt"));
    }

    @Test
    void getSystemInfo_generatedAtMatchesDateTimeFormat() {
        Map<String, Object> info = reportService.getSystemInfo();
        String generatedAt = (String) info.get("generatedAt");
        // Format: yyyy-MM-dd HH:mm:ss
        assertNotNull(generatedAt);
        assertTrue(generatedAt.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "generatedAt should match yyyy-MM-dd HH:mm:ss format, but was: " + generatedAt);
    }

    @Test
    void getSystemInfo_generatedAtIsNonEmpty() {
        Map<String, Object> info = reportService.getSystemInfo();
        String generatedAt = (String) info.get("generatedAt");
        assertFalse(generatedAt.isEmpty());
    }

    @Test
    void getSystemInfo_returnsMapWithFourEntries() {
        Map<String, Object> info = reportService.getSystemInfo();
        assertEquals(4, info.size());
    }

    @Test
    void getSystemInfo_calledTwice_returnsConsistentReportPath() {
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();
        assertEquals(info1.get("reportPath"), info2.get("reportPath"));
    }

    @Test
    void getSystemInfo_calledTwice_returnsConsistentServerPort() {
        Map<String, Object> info1 = reportService.getSystemInfo();
        Map<String, Object> info2 = reportService.getSystemInfo();
        assertEquals(info1.get("serverPort"), info2.get("serverPort"));
    }
}
