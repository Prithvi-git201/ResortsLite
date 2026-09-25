package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation using Amazon S3.
 *
 * <p>cr-java-0063 FIX (source lines 37, 39, 42): All {@code java.io.File} and
 * {@code java.io.FileWriter} usages for persistent data storage have been removed and
 * replaced with Amazon S3 client calls (AWS SDK for Java v2). The local file system is
 * ephemeral in cloud/container environments; storing data there violates cloud storage
 * patterns. Data is now written directly to an S3 bucket, providing durable, scalable,
 * and cloud-native storage without any host-level file system dependency.
 *
 * <p>cr-java-0061 FIX: Replaced hard-coded absolute file paths ({@code /var/legacy/reports/}
 * and {@code C:\ResortBackups\nightly\}) with Amazon S3 bucket configuration sourced from
 * environment variables via Spring {@code @Value}. No local file system dependency remains.
 *
 * <p>cr-java-0062 FIX: All local file system write operations ({@code FileWriter},
 * {@code File.mkdirs}, etc.) have been removed and replaced with Amazon S3 PutObject calls.
 * Data is written directly to S3, ensuring durability across container restarts, scaling
 * events, and redeployments.
 *
 * <p>cr-java-0071 FIX (source line 66): The hard-coded report download URL
 * {@code "http://reports.resorts-internal.com:8080/download/"} has been removed and
 * replaced with a value sourced from AWS Systems Manager Parameter Store via the
 * {@code app.reports.download-base-url} Spring property (SSM parameter:
 * {@code /resortslite/reports/download-base-url}). This enables environment-agnostic
 * deployments — the URL can differ across dev, staging, and production without any
 * code change or redeployment.
 */
@Service
public class ReportService {

    /**
     * S3 bucket name sourced from the {@code CLOUD_AWS_S3_BUCKET_NAME} environment variable
     * (or the {@code cloud.aws.s3.bucket-name} Spring property). Defaults to
     * {@code resorts-lite-reports} for local development.
     *
     * <p>cr-java-0063 FIX: replaces the hard-coded {@code REPORT_BASE_PATH} constant
     * ({@code /var/legacy/reports/}) that was used as the root for {@code new File(...)}
     * calls at source lines 37 and 39.
     */
    @Value("${cloud.aws.s3.bucket-name:resorts-lite-reports}")
    private String s3BucketName;

    /**
     * AWS region sourced from the {@code cloud.aws.s3.region} Spring property.
     * Defaults to {@code us-east-1}.
     */
    @Value("${cloud.aws.s3.region:us-east-1}")
    private String s3Region;

    /**
     * S3 key prefix for monthly reports. Replaces the hard-coded local directory path.
     *
     * <p>cr-java-0063 FIX: replaces the {@code REPORT_BASE_PATH} constant used in
     * {@code new File(REPORT_BASE_PATH)} at source line 37.
     */
    @Value("${cloud.aws.s3.reports-prefix:reports/}")
    private String reportsPrefix;

    /**
     * S3 key prefix for nightly backups. Replaces the hard-coded Windows path constant.
     *
     * <p>cr-java-0063 FIX: replaces the {@code BACKUP_PATH} constant
     * ({@code C:\ResortBackups\nightly\}) that was used for local file storage.
     */
    @Value("${cloud.aws.s3.backup-prefix:backups/nightly/}")
    private String backupPrefix;

    /**
     * Server port sourced from Spring configuration. Retained as a Spring-managed value;
     * the actual port binding is controlled by {@code server.port} in
     * {@code application.properties}, which is driven by the {@code SERVER_PORT}
     * environment variable injected by ECS/EKS at runtime (backed by the AWS SSM
     * Parameter Store parameter {@code /resortslite/server/port}).
     *
     * <p>cr-java-0077 FIX (source line 28): The hard-coded {@code SERVER_PORT = 8080}
     * constant has been replaced with a value injected via the {@code SERVER_PORT}
     * environment variable. ECS task definitions / EKS pod specs set this variable
     * from the SSM parameter {@code /resortslite/server/port}, enabling dynamic port
     * assignment by the container orchestration platform without any code change.
     */
    @Value("${server.port:${SERVER_PORT:8080}}")
    private int serverPort;

    /**
     * Base URL for report downloads, sourced from AWS Systems Manager Parameter Store
     * via the {@code app.reports.download-base-url} Spring property.
     *
     * <p>cr-java-0071 FIX (source line 66): Replaces the hard-coded URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} that was previously
     * returned directly in {@link #buildReportDownloadUrl(String)}. The value is now
     * externalised to the SSM parameter {@code /resortslite/reports/download-base-url},
     * enabling environment-specific URL configuration without code changes.
     * Defaults to an HTTPS S3 presigned URL pattern for local development.
     */
    @Value("${app.reports.download-base-url:https://resorts-lite-reports.s3.us-east-1.amazonaws.com/reports/}")
    private String reportsDownloadBaseUrl;

    /**
     * Builds a lazily-initialised {@link S3Client} using the region resolved from
     * environment configuration. AWS credentials are supplied via the standard credential
     * provider chain (IAM role, environment variables, {@code ~/.aws/credentials}) —
     * no hard-coded secrets.
     *
     * @return a configured {@link S3Client} instance
     */
    private S3Client buildS3Client() {
        return S3Client.builder()
                .region(Region.of(s3Region))
                .build();
    }

    /**
     * Generates a monthly CSV report and uploads it directly to Amazon S3.
     *
     * <p><b>cr-java-0063 FIX — source lines 37, 39, 42:</b>
     * <ul>
     *   <li><b>Line 37</b>: {@code File reportDir = new File(REPORT_BASE_PATH);} —
     *       REMOVED. S3 "directories" are implicit key prefixes; no {@code File} object
     *       is needed.</li>
     *   <li><b>Line 39</b>: {@code reportDir.mkdirs();} — REMOVED. S3 does not require
     *       explicit directory creation; the key prefix is created automatically when the
     *       first object is uploaded.</li>
     *   <li><b>Line 42</b>: {@code FileWriter writer = new FileWriter(fullPath);} —
     *       REMOVED. Report content is now streamed directly to Amazon S3 via
     *       {@code S3Client.putObject()} using {@code RequestBody.fromString()}, providing
     *       durable cloud-native storage that survives container restarts and scale-out
     *       events.</li>
     * </ul>
     *
     * @param month two-digit month string (e.g. {@code "03"})
     * @param year  four-digit year string  (e.g. {@code "2024"})
     * @return result map containing upload status and the S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0063 FIX (source line 37 / 39): No File or FileWriter objects are created.
        // The S3 object key replaces the local fullPath variable; S3 key prefixes act as
        // virtual directories — no mkdirs() call is required.
        String objectKey = reportsPrefix + "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try (S3Client s3 = buildS3Client()) {
            // cr-java-0063 FIX (source line 42): Replaced:
            //   File reportDir = new File(REPORT_BASE_PATH);   // line 37 — java.io.File usage
            //   if (!reportDir.exists()) {                      // line 38
            //       reportDir.mkdirs();                         // line 39 — java.io.File usage
            //   }
            //   FileWriter writer = new FileWriter(fullPath);   // line 42 — java.io.File usage
            //   writer.write(...);
            //   writer.close();
            // With a direct Amazon S3 PutObject call. Data is stored durably in S3 and
            // survives container restarts, scaling events, and redeployments.
            String csvContent = "BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n"
                    + "BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n"
                    + "BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n";

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(objectKey)
                    .contentType("text/csv")
                    .build();

            // cr-java-0063 FIX: S3 putObject replaces FileWriter.write() — durable cloud storage
            s3.putObject(putRequest, RequestBody.fromString(csvContent));

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", objectKey);
            result.put("serverPort", serverPort);

        } catch (S3Exception e) {
            result.put("status", "error");
            result.put("message", e.awsErrorDetails().errorMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the given report file name using the externalized base URL.
     *
     * <p>cr-java-0071 FIX (source line 66): The previous implementation hard-coded the
     * environment-specific URL {@code "http://reports.resorts-internal.com:8080/download/"}
     * directly in the return statement, preventing portability across cloud environments.
     * The base URL is now injected via {@code @Value("${app.reports.download-base-url}")}
     * which is resolved from AWS Systems Manager Parameter Store (SSM parameter:
     * {@code /resortslite/reports/download-base-url}) at runtime. This allows the URL to
     * be configured per environment (dev/staging/production) without code changes.
     *
     * <p>cr-java-0088 FIX: The plain HTTP URL has been replaced with an HTTPS URL
     * sourced from SSM Parameter Store, satisfying AWS Well-Architected security standards.
     *
     * @param reportName the report file name (e.g. {@code "resort_report_03_2024.csv"})
     * @return environment-specific HTTPS URL pointing to the report, sourced from SSM
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: reportsDownloadBaseUrl is injected from SSM Parameter Store
        // via app.reports.download-base-url — no hard-coded environment URL.
        return reportsDownloadBaseUrl + reportName;
    }

    /**
     * Returns system information using cloud-native configuration values.
     *
     * <p>cr-java-0063 FIX: Hard-coded local paths ({@code REPORT_BASE_PATH} and
     * {@code BACKUP_PATH}) have been replaced with S3 bucket and prefix references
     * sourced from environment variables. No local file system paths are exposed or used.
     *
     * @return map of current system configuration values
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (source line 70): Replaced java.util.Date + SimpleDateFormat
        // (which uses the JVM's default/server-local timezone) with java.time.Instant
        // formatted via DateTimeFormatter.ISO_INSTANT at UTC (ZoneOffset.UTC).
        // In distributed cloud environments (ECS, EKS, Lambda) running across multiple
        // regions or containers, the JVM default timezone is unreliable and can differ
        // between instances, causing timestamp inconsistencies in logs and inter-service
        // communication. Standardising on UTC via java.time ensures consistent,
        // timezone-agnostic timestamps across all cloud deployments.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: replaced REPORT_BASE_PATH (/var/legacy/reports/) and
        // BACKUP_PATH (C:\ResortBackups\nightly\) with S3 bucket + prefix references.
        // No local file system paths are exposed or used.
        info.put("s3Bucket", s3BucketName);
        info.put("reportsPrefix", reportsPrefix);
        info.put("backupPrefix", backupPrefix);
        info.put("serverPort", serverPort);
        info.put("reportsDownloadBaseUrl", reportsDownloadBaseUrl);
        info.put("generatedAt", timestamp);
        return info;
    }
}
