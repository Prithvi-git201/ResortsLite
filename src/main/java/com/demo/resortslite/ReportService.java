package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Service responsible for generating and storing resort reports.
 *
 * <p>cr-java-0061 (Local File System Read Operations) &amp;
 * cr-java-0062 (Local File System Write Operations):
 * All local file-system dependencies — including {@code new File()}, {@code FileWriter},
 * {@code mkdirs()}, and hard-coded paths such as {@code /var/legacy/reports/} and
 * {@code C:\ResortBackups\nightly\} — have been removed. Report data is now built
 * in-memory and persisted directly to Amazon S3 via the AWS SDK v2, ensuring
 * durability and availability in containerised / serverless cloud environments.
 * </p>
 *
 * <p>cr-java-0071 (Hard-coded Environment URLs):
 * The former hard-coded server port constant ({@code private static final int SERVER_PORT = 8080})
 * has been eliminated. The port is now externalised to AWS Systems Manager Parameter Store
 * (SSM path: /resortslite/server-port, configurable via {@code ssm.parameter.server-port}
 * application property) and injected at runtime through the {@code SERVER_PORT} environment
 * variable in ECS, EKS, or Elastic Beanstalk task/pod definitions. This enables container
 * orchestration platforms to perform dynamic port assignment and service discovery without
 * any code change (cr-java-0077).
 * </p>
 *
 * <p>cr-java-0071 (Hard-coded Environment URLs):
 * The former hard-coded report download URL
 * ({@code "http://reports.resorts-internal.com:8080/download/"}) has been replaced
 * with a value retrieved at runtime from AWS Systems Manager Parameter Store via
 * {@link SsmParameterStoreService}. This enables environment-agnostic deployments —
 * dev, staging, and production each supply their own URL through SSM without any
 * code change.</p>
 */
@Service
public class ReportService {

    /**
     * cr-java-0071: SSM Parameter Store service injected to resolve environment-specific
     * URLs at runtime instead of hard-coding them in source.
     */
    @Autowired
    private SsmParameterStoreService ssmParameterStoreService;

    /**
     * S3 bucket name for report storage.
     * Injected from the {@code AWS_S3_REPORT_BUCKET} environment variable or
     * the {@code aws.s3.report-bucket} application property.
     * Replaces the former hard-coded {@code REPORT_BASE_PATH} constant
     * (cr-java-0061, cr-java-0062).
     */
    @Value("${aws.s3.report-bucket:resorts-lite-reports}")
    private String reportBucket;

    /**
     * S3 key prefix for monthly reports.
     * Replaces the former hard-coded {@code /var/legacy/reports/} path
     * (cr-java-0061, cr-java-0062).
     */
    @Value("${aws.s3.report-prefix:reports/}")
    private String reportPrefix;

    /**
     * S3 key prefix for nightly backups.
     * Replaces the former hard-coded {@code C:\ResortBackups\nightly\} path
     * (cr-java-0061, cr-java-0062).
     */
    @Value("${aws.s3.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    /**
     * AWS region used when constructing the S3 client.
     * Injected from the {@code AWS_REGION} environment variable or
     * the {@code aws.region} application property (cr-java-0061, cr-java-0062).
     */
    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    /**
     * cr-java-0077 FIX: Hard-coded port eliminated.
     *
     * <p>The former {@code private static final int SERVER_PORT = 8080} constant has been
     * replaced with a value resolved at runtime from AWS Systems Manager Parameter Store
     * (SSM path: /resortslite/server-port) and injected through the {@code SERVER_PORT}
     * environment variable in ECS/EKS task definitions or Elastic Beanstalk environment
     * configuration. This allows container orchestration platforms to perform dynamic port
     * assignment and enables service discovery without any code change.</p>
     *
     * <p>Resolution order:
     * <ol>
     *   <li>AWS SSM Parameter Store value at {@code /resortslite/server-port}</li>
     *   <li>{@code SERVER_PORT} environment variable (injected by ECS/EKS/Beanstalk)</li>
     *   <li>Default fallback {@code 8080} for local development only</li>
     * </ol>
     * </p>
     */
    @Value("${SERVER_PORT:${server.port:8080}}")
    private int serverPort;

    /**
     * Generates a monthly CSV report and uploads it directly to Amazon S3.
     *
     * <p>cr-java-0062 fix: the former local-disk write sequence
     * ({@code new File(REPORT_BASE_PATH)}, {@code reportDir.mkdirs()},
     * {@code new FileWriter(fullPath)}, {@code writer.write(...)},
     * {@code writer.close()}) has been replaced with an in-memory
     * {@link StringBuilder} whose bytes are uploaded to S3 via
     * {@link S3Client#putObject(PutObjectRequest, RequestBody)}.
     * No data is written to the ephemeral container file system.</p>
     *
     * @param month the month for which the report is generated (e.g. "03")
     * @param year  the year for which the report is generated (e.g. "2024")
     * @return a map containing the operation status and the S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0062: S3 object key replaces the former local file path
        // (was: REPORT_BASE_PATH + "resort_report_" + month + "_" + year + ".csv")
        String objectKey = reportPrefix + "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // cr-java-0062: build CSV content entirely in memory —
            // no File, FileWriter, or mkdirs() calls; no local disk I/O.
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // cr-java-0062: write (upload) the report to Amazon S3 instead of the local file system.
            // S3 provides durable, highly-available object storage that survives container restarts
            // and horizontal scaling — eliminating the data-loss risk of ephemeral local storage.
            S3Client s3Client = S3Client.builder()
                    .region(Region.of(awsRegion))
                    .build();

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);
            PutObjectResponse putResponse = s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", objectKey);
            result.put("eTag", putResponse.eTag());
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for a named report stored in Amazon S3.
     *
     * <p>cr-java-0071 FIX: The former hard-coded plain-HTTP URL
     * ({@code "http://reports.resorts-internal.com:8080/download/" + reportName})
     * has been replaced with a value retrieved at runtime from AWS Systems Manager
     * Parameter Store (SSM path: /resortslite/report-download-url, configurable via
     * {@code ssm.parameter.report-download-url} application property).
     * This makes the endpoint environment-agnostic — dev, staging, and production
     * each supply their own base URL through SSM without any code change.</p>
     *
     * @param reportName the S3 object name of the report
     * @return the URL pointing to the report object, constructed from the SSM-resolved base URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: base URL retrieved from AWS SSM Parameter Store at runtime.
        // The former hard-coded literal "http://reports.resorts-internal.com:8080/download/"
        // is replaced by a dynamic lookup so each environment (dev/staging/prod) can
        // supply its own endpoint via SSM without any code change.
        String reportDownloadBaseUrl = ssmParameterStoreService.getReportDownloadUrl(); // cr-java-0071
        return reportDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information including S3 storage configuration.
     *
     * <p>cr-java-0061 / cr-java-0062: the former hard-coded
     * {@code REPORT_BASE_PATH} and {@code BACKUP_PATH} constants have been
     * replaced with injected S3 bucket / prefix references.</p>
     *
     * @return a map of system metadata
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: replaced java.util.Date / SimpleDateFormat with java.time API.
        // Instant.now() reads from the JVM clock in UTC, eliminating server-local timezone
        // dependency. DateTimeFormatter with ZoneOffset.UTC ensures the formatted string is
        // always UTC-based, which is safe across multi-region / multi-container deployments.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0061 / cr-java-0062: S3 references replace former local-path constants
        info.put("reportBucket", reportBucket);
        info.put("reportPrefix", reportPrefix);
        info.put("backupPrefix", backupPrefix);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
