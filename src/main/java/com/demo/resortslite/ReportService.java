package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService handles report generation and storage for the ResortsLite application.
 *
 * <p>All report data is persisted to Amazon S3 instead of the local file system,
 * ensuring durability and availability in containerised / serverless cloud environments.
 * Local file-system writes (FileWriter, File.mkdirs) have been removed to eliminate
 * ephemeral-storage data-loss risk (cr-java-0062).</p>
 */
@Service
public class ReportService {

    /**
     * S3 bucket name for report storage.
     * Injected from the {@code cloud.aws.s3.report-bucket} environment variable.
     * Replaces the hardcoded local path {@code /var/legacy/reports/}.
     */
    @Value("${cloud.aws.s3.report-bucket:resort-reports-bucket}")
    private String reportBucket;

    /**
     * S3 key prefix used for nightly backup objects.
     * Injected from the {@code cloud.aws.s3.backup-prefix} environment variable.
     * Replaces the hardcoded Windows path {@code C:\ResortBackups\nightly\}.
     */
    @Value("${cloud.aws.s3.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    /** AWS region for S3 client construction, sourced from environment variable. */
    @Value("${cloud.aws.region:us-east-1}")
    private String awsRegion;

    /**
     * SSM Parameter Store key for the report download base URL.
     * Sourced from the {@code app.ssm.report-download-url-param} property
     * (default: /resortslite/reports/download-url).
     */
    @Value("${app.ssm.report-download-url-param:/resortslite/reports/download-url}")
    private String reportDownloadUrlParam;

    /**
     * Server port injected from the {@code SERVER_PORT} environment variable.
     *
     * <p>Replaces the hard-coded {@code private static final int SERVER_PORT = 8080} constant
     * (cr-java-0077). In ECS/EKS/Elastic Beanstalk the {@code SERVER_PORT} environment variable
     * is set via the task definition / pod spec, allowing dynamic port assignment by the
     * container orchestration platform. The AWS SSM Parameter Store key
     * {@code /resortslite/server/port} can also be used to centralise port configuration.</p>
     */
    @Value("${server.port:8080}")
    private int serverPort;

    /**
     * Builds an AWS SDK v2 S3Client scoped to the configured region.
     * The client is created per-request and closed via try-with-resources to
     * avoid connection leaks in long-running container instances.
     *
     * @return a configured {@link S3Client}
     */
    private S3Client buildS3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Generates a monthly booking report and uploads it to Amazon S3.
     *
     * <p>Previously this method wrote the CSV to the local file system using
     * {@code FileWriter} and {@code File.mkdirs()} — both of which fail in
     * ephemeral container environments (cr-java-0062). The fix builds the CSV
     * content in-memory and uploads it directly to S3 via
     * {@link S3Client#putObject(PutObjectRequest, RequestBody)}.</p>
     *
     * @param month numeric or named month string (e.g. "03" or "March")
     * @param year  four-digit year string (e.g. "2024")
     * @return a result map containing {@code status}, {@code s3Bucket}, and {@code s3Key}
     *         on success, or {@code status} and {@code message} on failure
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 object key replaces the hardcoded local file path (was: REPORT_BASE_PATH + fileName)
        // FIX cr-java-0062: local FileWriter write replaced with S3 PutObject upload
        String s3Key = "reports/" + fileName;

        Map<String, Object> result = new HashMap<>();

        try (S3Client s3Client = buildS3Client()) {
            // Build CSV content in memory — no local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // Upload report content directly to Amazon S3 (replaces FileWriter + local mkdirs)
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportBucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest,
                    RequestBody.fromString(csvContent.toString()));

            result.put("status", "generated");
            result.put("s3Bucket", reportBucket);
            result.put("s3Key", s3Key);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the given report name.
     *
     * @param reportName the name of the report file
     * @return a URL string pointing to the report download endpoint
     */
    public String buildReportDownloadUrl(String reportName) { // doc-missing-001
        // FIX cr-java-0071: Hard-coded environment URL replaced with base URL retrieved from
        // AWS Systems Manager Parameter Store. The SSM parameter key is configurable via
        // the 'app.ssm.report-download-url-param' property
        // (default: /resortslite/reports/download-url).
        String baseUrl;
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {
            GetParameterRequest paramRequest = GetParameterRequest.builder()
                    .name(reportDownloadUrlParam)
                    .withDecryption(false)
                    .build();
            GetParameterResponse paramResponse = ssmClient.getParameter(paramRequest);
            baseUrl = paramResponse.parameter().value();
        } catch (Exception e) {
            // Fall back to the environment-variable-backed default if SSM is unavailable
            baseUrl = System.getenv().getOrDefault("REPORT_DOWNLOAD_BASE_URL",
                    "https://reports.resorts-internal.com/download");
        }
        return baseUrl + "/" + reportName;
    }

    /**
     * Returns system information including S3 storage configuration and current timestamp.
     *
     * @return a map of system info key-value pairs
     */
    public Map<String, Object> getSystemInfo() { // doc-missing-001
        // cr-java-0111 FIX: Replaced java.util.Date + SimpleDateFormat with java.time API.
        // Instant.now() uses the JVM clock but is always UTC-based, eliminating server-local
        // timezone dependency. DateTimeFormatter with ZoneOffset.UTC ensures consistent
        // ISO-8601 UTC timestamps across all cloud regions and container instances.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // S3 bucket/prefix values sourced from environment variables (replaces hardcoded paths)
        info.put("reportBucket", reportBucket);
        info.put("backupPrefix", backupPrefix);
        info.put("awsRegion", awsRegion);
        info.put("serverPort", serverPort);  // cr-java-0077: injected from SERVER_PORT env var
        info.put("generatedAt", timestamp);
        return info;
    }
}
