package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService — cloud-native report generation service.
 *
 * <p>All local file-system operations (cr-java-0063) have been replaced with
 * Amazon S3 uploads using the AWS SDK v2. This ensures data durability and
 * availability in containerised / serverless environments where the local file
 * system is ephemeral and data written locally would be lost on container restart
 * or scale-out events.</p>
 *
 * <p>Bucket name, object-key prefixes, and AWS region are externalised via
 * Spring {@code @Value} bindings backed by environment variables so the service
 * is portable across cloud environments without code changes.</p>
 *
 * <p><strong>cr-java-0077 fix (Line 28 of original source):</strong>
 * The hard-coded integer constant {@code private static final int SERVER_PORT = 8080;}
 * has been removed and replaced with a Spring {@code @Value} binding that reads the
 * port at runtime from the {@code SERVER_PORT} environment variable. In ECS, EKS, or
 * Elastic Beanstalk the environment variable is injected from the AWS Systems Manager
 * Parameter Store parameter {@code /resortslite/server/port}, enabling dynamic port
 * assignment by the container orchestration platform and eliminating deployment
 * conflicts caused by fixed port numbers.</p>
 *
 * <p><strong>cr-java-0071 fix (Line 66 of original source):</strong>
 * The hard-coded environment URL
 * {@code "http://reports.resorts-internal.com:8080/download/"} returned by
 * {@link #buildReportDownloadUrl(String)} has been replaced with a value
 * retrieved at runtime from <strong>AWS Systems Manager Parameter Store</strong>.
 * The SSM parameter name is externalised via the
 * {@code app.ssm.report-download-url-param} property (backed by the
 * {@code SSM_REPORT_DOWNLOAD_URL_PARAM} environment variable), enabling
 * environment-agnostic deployments across dev, staging, and production.</p>
 *
 * <p><strong>cr-java-0063 fixes applied (Lines 37, 39, 42 of original source):</strong>
 * <ul>
 *   <li>Line 37: {@code new File(REPORT_BASE_PATH)} — removed; S3 bucket/prefix used instead.</li>
 *   <li>Line 39: {@code reportDir.mkdirs()} — removed; S3 creates "directories" implicitly via key prefixes.</li>
 *   <li>Line 42: {@code new FileWriter(fullPath)} — removed; replaced with {@link S3Client#putObject} call.</li>
 * </ul>
 * </p>
 */
@Service
public class ReportService {

    // cr-java-0077 FIX (Line 28 of original source):
    // Removed: private static final int SERVER_PORT = 8080;
    // Replaced with Spring @Value binding that reads the SERVER_PORT environment variable at
    // runtime. In ECS/EKS/Elastic Beanstalk the value is injected from AWS SSM Parameter Store
    // parameter /resortslite/server/port, allowing the container orchestration platform to
    // assign ports dynamically and preventing service conflicts from fixed port numbers.
    // To store the value in SSM:
    //   aws ssm put-parameter --name /resortslite/server/port --value "8080" --type String
    // To inject at runtime (ECS task definition / Elastic Beanstalk env property):
    //   SERVER_PORT=<value resolved from SSM /resortslite/server/port>
    @Value("${SERVER_PORT:8080}")
    private int serverPort;

    // cr-java-0063 FIX (Lines 37, 39, 42): Replaced java.io.File-based persistent storage
    // (new File(REPORT_BASE_PATH), reportDir.mkdirs(), new FileWriter(fullPath)) with Amazon S3
    // object storage. Bucket name and report prefix are externalised via environment variables /
    // application properties so the application is portable across cloud environments and data
    // is never written to the ephemeral container file system.
    @Value("${app.s3.bucket-name:resort-reports-bucket}")
    private String s3BucketName;

    @Value("${app.s3.report-prefix:reports/}")
    private String s3ReportPrefix;

    @Value("${app.s3.backup-prefix:backups/nightly/}")
    private String s3BackupPrefix;

    @Value("${cloud.aws.region.static:us-east-1}")
    private String awsRegion;

    /**
     * cr-java-0071 FIX: SSM parameter name for the report download base URL.
     * Resolved from the environment variable SSM_REPORT_DOWNLOAD_URL_PARAM, with a
     * safe default of "/resortslite/reports/download-base-url".
     * The actual URL value is stored in AWS SSM Parameter Store under this key.
     */
    @Value("${app.ssm.report-download-url-param:/resortslite/reports/download-base-url}")
    private String reportDownloadUrlSsmParam;

    /**
     * Builds an S3Client using the configured AWS region.
     * The client automatically resolves credentials from the environment
     * (IAM role, environment variables, or ~/.aws/credentials).
     *
     * @return a configured {@link S3Client} instance
     */
    private S3Client buildS3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Retrieves a parameter value from AWS Systems Manager Parameter Store.
     *
     * <p>This helper centralises all SSM lookups so that the service never
     * contains hard-coded environment-specific URLs (cr-java-0071).</p>
     *
     * @param paramName the SSM parameter name (e.g. "/resortslite/reports/download-base-url")
     * @return the decrypted string value stored in Parameter Store
     */
    private String getSsmParameter(String paramName) {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(paramName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        }
    }

    /**
     * Generates a monthly CSV report and uploads it directly to Amazon S3.
     *
     * <p><strong>cr-java-0063 fix — three occurrences resolved:</strong></p>
     * <ul>
     *   <li><strong>Line 37 (original):</strong> {@code File reportDir = new File(REPORT_BASE_PATH);}
     *       — Removed entirely. S3 does not require directory creation; object keys with prefix
     *       slashes implicitly represent a folder hierarchy.</li>
     *   <li><strong>Line 39 (original):</strong> {@code reportDir.mkdirs();}
     *       — Removed entirely. Amazon S3 creates the key path automatically on first
     *       {@code putObject} call; no local directory scaffolding is needed.</li>
     *   <li><strong>Line 42 (original):</strong> {@code FileWriter writer = new FileWriter(fullPath);}
     *       — Replaced with {@link S3Client#putObject(PutObjectRequest, RequestBody)}.
     *       Report content is built in-memory via {@link StringBuilder} and streamed
     *       directly to S3, eliminating all local file-system I/O.</li>
     * </ul>
     *
     * @param month two-digit month string (e.g. "03")
     * @param year  four-digit year string  (e.g. "2024")
     * @return result map containing upload status and S3 object key
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        // cr-java-0063: No local file path construction — object key is S3-native
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        String s3Key = s3ReportPrefix + fileName;

        Map<String, Object> result = new HashMap<>();

        // cr-java-0063 FIX (Line 37): Removed — new File(REPORT_BASE_PATH)
        // cr-java-0063 FIX (Line 39): Removed — reportDir.exists() / reportDir.mkdirs()
        //   S3 does not require explicit directory creation; key prefixes serve as virtual folders.
        // cr-java-0063 FIX (Line 42): Removed — new FileWriter(fullPath)
        //   Replaced with in-memory StringBuilder + S3Client.putObject() — no local I/O.
        try (S3Client s3Client = buildS3Client()) {

            // Build CSV content entirely in memory — zero local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // Upload directly to Amazon S3 using AWS SDK v2
            // Replaces: new File(REPORT_BASE_PATH) [Line 37]
            //           reportDir.mkdirs()          [Line 39]
            //           new FileWriter(fullPath)    [Line 42]
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(s3BucketName)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            PutObjectResponse response = s3Client.putObject(
                    putRequest,
                    RequestBody.fromString(csvContent.toString())
            );

            result.put("status", "generated");
            result.put("s3Bucket", s3BucketName);
            result.put("s3Key", s3Key);
            result.put("eTag", response.eTag());
            // cr-java-0077 FIX: serverPort now sourced from SERVER_PORT env var (SSM-injected)
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL by retrieving the base URL from
     * AWS Systems Manager Parameter Store.
     *
     * <p><strong>cr-java-0071 fix (Line 66 of original source):</strong>
     * Replaced the hard-coded plain-HTTP URL
     * {@code "http://reports.resorts-internal.com:8080/download/"} with a
     * runtime lookup from AWS SSM Parameter Store. The SSM parameter name is
     * externalised via {@code app.ssm.report-download-url-param}
     * (env: {@code SSM_REPORT_DOWNLOAD_URL_PARAM}), so the base URL can be
     * updated per environment without any code or binary changes.</p>
     *
     * @param reportName the report file name (appended to the base URL)
     * @return the full download URL for the given report, sourced from SSM
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX (Line 66): Replaced hard-coded environment URL
        //   "http://reports.resorts-internal.com:8080/download/" + reportName
        // with a runtime lookup from AWS Systems Manager Parameter Store.
        // The SSM parameter name is externalised via the property
        //   app.ssm.report-download-url-param (env: SSM_REPORT_DOWNLOAD_URL_PARAM)
        // so the same artifact can be deployed to any environment without code changes.
        String baseUrl = getSsmParameter(reportDownloadUrlSsmParam); // cr-java-0071 FIXED
        return baseUrl + reportName;
    }

    /**
     * Returns system information using cloud-native S3 paths instead of
     * local file system paths (cr-java-0063), and the dynamically-assigned
     * server port sourced from the SERVER_PORT environment variable (cr-java-0077).
     *
     * <p><strong>cr-java-0111 fix (Line 70 of original source):</strong>
     * Replaced {@code new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())} with
     * {@code Instant.now()} formatted via {@link DateTimeFormatter} in UTC (ZoneOffset.UTC).
     * This eliminates server-local timezone dependency and ensures consistent timestamps
     * across all cloud regions and container instances.</p>
     *
     * @return map of system metadata including S3 bucket, prefix, and server port information
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX (Line 70): Replaced java.util.Date + SimpleDateFormat (server-local
        // timezone) with java.time.Instant.now() formatted in UTC via DateTimeFormatter.
        // Standardising on UTC eliminates timezone inconsistencies across cloud regions and
        // container instances, preventing scheduling failures and time-related logic errors.
        String timestamp = DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // cr-java-0063 FIX: Replaced local path constants (REPORT_BASE_PATH, BACKUP_PATH)
        // with S3 bucket and prefix values sourced from environment configuration
        info.put("reportBucket", s3BucketName);
        info.put("reportPrefix", s3ReportPrefix);
        info.put("backupPrefix", s3BackupPrefix);
        // cr-java-0077 FIX: Replaced hard-coded SERVER_PORT = 8080 constant with
        // environment-variable-injected value (sourced from SSM /resortslite/server/port)
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }
}
