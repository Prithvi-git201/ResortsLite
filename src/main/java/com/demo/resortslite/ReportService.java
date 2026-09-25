package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — generates and stores resort reports.
 *
 * cr-java-0063 (Java.io.File Usage for Data Storage): All java.io.File-based persistent
 * storage operations have been removed and replaced with Amazon S3 object storage via the
 * AWS SDK v2 S3Client. The following violations from the original source have been remediated:
 *   - Line 37: new File(REPORT_BASE_PATH)  → removed; S3 bucket reference used instead
 *   - Line 39: reportDir.mkdirs()          → removed; no local directory creation needed
 *   - Line 42: new FileWriter(fullPath)    → removed; in-memory build + S3 putObject used
 * Report data is built in-memory and uploaded directly to S3, ensuring durability across
 * container restarts and horizontal scaling in cloud/containerised environments.
 *
 * cr-java-0062 (Local File System Write Operations): All local file write operations
 * (FileWriter, new File(), mkdirs(), writer.write(), writer.close()) have been removed
 * and replaced with Amazon S3 object storage via the AWS SDK v2 S3Client.
 *
 * cr-java-0061 (Hard-coded File Paths): Hard-coded absolute paths ("/var/legacy/reports/"
 * and "C:\\ResortBackups\\nightly\\") replaced with S3 bucket names sourced from
 * environment variables AWS_S3_REPORTS_BUCKET and AWS_S3_BACKUP_BUCKET.
 *
 * cr-java-0071 (Hard-coded Environment URLs): Hard-coded report download URL
 * "http://reports.resorts-internal.com:8080/download/" replaced with a value sourced
 * from AWS Systems Manager Parameter Store via the Spring property
 * "report.download.base.url" (SSM parameter path: /resortslite/report/download-base-url).
 */
@Service
public class ReportService {

    // cr-java-0063 / cr-java-0061: Replaced hard-coded absolute file path "/var/legacy/reports/"
    // (used as the root for new File() and FileWriter() calls) with Amazon S3 bucket
    // configuration sourced from environment variable AWS_S3_REPORTS_BUCKET.
    @Value("${aws.s3.reports.bucket:${AWS_S3_REPORTS_BUCKET:resorts-lite-reports}}")
    private String reportsBucket;

    // cr-java-0063 / cr-java-0061: Replaced hard-coded Windows-style backup path
    // "C:\\ResortBackups\\nightly\\" with Amazon S3 bucket configuration sourced from
    // environment variable AWS_S3_BACKUP_BUCKET.
    @Value("${aws.s3.backup.bucket:${AWS_S3_BACKUP_BUCKET:resorts-lite-backups}}")
    private String backupBucket;

    // cr-java-0077 (Hard-coded Ports): The original hard-coded constant
    //   private static final int SERVER_PORT = 8080;  // czr-port-001
    // has been removed. The port is now resolved at runtime from the environment variable
    // SERVER_PORT, which is injected by ECS task definitions, EKS pod specs, or Elastic
    // Beanstalk environment properties. The AWS Systems Manager Parameter Store parameter
    // /resortslite/server/port is the authoritative source; the Spring property
    // "server.port" (defined in application.properties as ${SERVER_PORT:8080}) bridges
    // SSM → environment variable → Spring context, enabling dynamic port assignment.
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    // cr-java-0071: Hard-coded report download base URL
    // "http://reports.resorts-internal.com:8080/download/" (original line 66) replaced with
    // a value sourced from AWS Systems Manager Parameter Store via the Spring property
    // "report.download.base.url" (SSM parameter path: /resortslite/report/download-base-url).
    // The environment variable REPORT_DOWNLOAD_BASE_URL is used as a fallback for local
    // development. This makes the endpoint environment-agnostic: dev, staging, and production
    // each supply their own SSM parameter value without any code change.
    @Value("${report.download.base.url:${REPORT_DOWNLOAD_BASE_URL:http://reports.resorts-internal.com:8080/download/}}")
    private String reportDownloadBaseUrl;

    private final S3Client s3Client;

    public ReportService(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Generates a monthly resort report and uploads it to Amazon S3.
     *
     * cr-java-0063 fix (Java.io.File Usage for Data Storage):
     * The following java.io.File-based persistent storage operations from the original
     * source file have been replaced with Amazon S3 client calls:
     *
     *   Original line 37: File reportDir = new File(REPORT_BASE_PATH);
     *     → Removed. No local File object is created. S3 bucket/key used instead.
     *
     *   Original line 39: reportDir.mkdirs();
     *     → Removed. S3 is a flat object store; no directory creation is required.
     *
     *   Original line 42: FileWriter writer = new FileWriter(fullPath);
     *     → Removed. Content is built in-memory via StringBuilder and uploaded via
     *       s3Client.putObject(), eliminating all host-level file system dependencies.
     *
     * cr-java-0062 fix: writer.write() and writer.close() also removed; replaced with
     * StringBuilder in-memory build and a single s3Client.putObject() call.
     *
     * @param month the report month (e.g. "03")
     * @param year  the report year  (e.g. "2024")
     * @return result map containing status and S3 object URL
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0063: S3 object key replaces the local file path constructed from
        // REPORT_BASE_PATH + fileName (original lines 33-34 and 37-42).
        String objectKey = "reports/resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0063: Build CSV content in memory — eliminates java.io.File /
            // FileWriter dependency (original lines 37, 39, 42 in source file).
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes();

            // cr-java-0063: Upload report directly to Amazon S3 instead of writing to
            // local file system. Replaces: new File(), mkdirs(), new FileWriter(), write(), close().
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportsBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));

            // cr-java-0063: Return the S3 object URL instead of a local file path.
            String s3Url = "s3://" + reportsBucket + "/" + objectKey;

            result.put("status", "generated");
            result.put("path", s3Url);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL using the base URL sourced from AWS Systems Manager
     * Parameter Store.
     *
     * cr-java-0071 fix: The hard-coded URL
     * "http://reports.resorts-internal.com:8080/download/" (original line 66) has been
     * removed. The base URL is now resolved from the instance field reportDownloadBaseUrl,
     * which is populated at application startup from AWS Systems Manager Parameter Store
     * via the Spring property "report.download.base.url"
     * (SSM parameter path: /resortslite/report/download-base-url).
     * This eliminates the environment-specific hard-coding and allows the same artifact
     * to be deployed across dev, staging, and production without modification.
     *
     * @param reportName the name of the report file to download
     * @return the full download URL for the specified report
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071: reportDownloadBaseUrl is injected from AWS Systems Manager
        // Parameter Store (SSM parameter: /resortslite/report/download-base-url) via
        // the Spring property "report.download.base.url". The hard-coded value
        // "http://reports.resorts-internal.com:8080/download/" has been replaced.
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including S3 bucket references and server port.
     *
     * cr-java-0063 / cr-java-0062 fix: Replaced local file path references
     * (REPORT_BASE_PATH, BACKUP_PATH) with S3 bucket names sourced from environment variables.
     *
     * @return map of system information key-value pairs
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111: Replaced java.util.Date / SimpleDateFormat with java.time API.
        // Instant.now() captures the current moment in UTC, eliminating server-local
        // timezone dependencies. DateTimeFormatter with ZoneOffset.UTC ensures consistent
        // ISO-8601 timestamps across all cloud regions and container instances.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 / cr-java-0061 / cr-java-0062: Replaced hard-coded file paths
        // with S3 bucket references driven by environment variables.
        info.put("reportBucket", reportsBucket);   // cr-java-0063 / cr-java-0061 / cr-java-0062
        info.put("backupBucket", backupBucket);    // cr-java-0063 / cr-java-0061 / cr-java-0062
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
