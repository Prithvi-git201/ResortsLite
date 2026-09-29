package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Service responsible for generating and managing resort reports.
 * All report storage is handled via Amazon S3 to ensure cloud-native,
 * ephemeral-filesystem-safe operation.
 *
 * Hard-coded environment-specific URLs (cr-java-0071) are replaced with values
 * retrieved at runtime from AWS Systems Manager Parameter Store, enabling
 * environment-agnostic deployments across dev, staging, and production without
 * any code changes.
 */
@Service
public class ReportService {

    /**
     * S3 bucket name injected from environment variable / application property.
     * Set the environment variable REPORT_S3_BUCKET (or property report.s3.bucket)
     * to the target S3 bucket name before deployment.
     */
    @Value("${report.s3.bucket:${REPORT_S3_BUCKET:resort-reports-bucket}}")
    private String reportS3Bucket;

    /**
     * S3 key prefix (folder) for report objects.
     * Defaults to "reports/" but can be overridden via property report.s3.prefix
     * or environment variable REPORT_S3_PREFIX.
     */
    @Value("${report.s3.prefix:${REPORT_S3_PREFIX:reports/}}")
    private String reportS3Prefix;

    /**
     * S3 key prefix (folder) for backup objects.
     * Defaults to "backups/nightly/" but can be overridden via property report.s3.backup.prefix
     * or environment variable REPORT_S3_BACKUP_PREFIX.
     */
    @Value("${report.s3.backup.prefix:${REPORT_S3_BACKUP_PREFIX:backups/nightly/}}")
    private String backupS3Prefix;

    /**
     * AWS region for S3 and SSM operations.
     * Defaults to us-east-1 but can be overridden via property aws.region
     * or the standard AWS_REGION / AWS_DEFAULT_REGION environment variables.
     */
    @Value("${aws.region:${AWS_REGION:us-east-1}}")
    private String awsRegion;

    /**
     * AWS SSM Parameter Store parameter name that holds the report download base URL.
     * cr-java-0071 FIX: The hard-coded environment-specific URL
     * "http://reports.resorts-internal.com:8080/download/" is replaced by a reference
     * to an SSM parameter, allowing each environment (dev/staging/prod) to store its
     * own URL in Parameter Store without requiring code changes.
     *
     * Set the environment variable REPORT_DOWNLOAD_URL_PARAM or the application property
     * report.download.url.param to the SSM parameter name for the target environment.
     * Example: /resortslite/prod/report-download-url
     */
    @Value("${report.download.url.param:${REPORT_DOWNLOAD_URL_PARAM:/resortslite/report-download-url}}")
    private String reportDownloadUrlParam;

    /**
     * Generates a monthly resort report and uploads it to Amazon S3.
     *
     * @param month the month for which the report is generated (e.g. "03")
     * @param year  the year for which the report is generated (e.g. "2024")
     * @return a map containing the operation status and the S3 object key of the uploaded report
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        // S3 object key replaces the former absolute local file path
        String s3Key = reportS3Prefix + fileName;

        Map<String, Object> result = new HashMap<>();

        try {
            // Build CSV content in memory — no local file system dependency
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] contentBytes = csvContent.toString().getBytes(StandardCharsets.UTF_8);

            // Upload the report to S3 using AWS SDK v2
            S3Client s3Client = buildS3Client();
            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(reportS3Bucket)
                    .key(s3Key)
                    .contentType("text/csv")
                    .build();

            s3Client.putObject(putRequest, RequestBody.fromBytes(contentBytes));
            s3Client.close();

            result.put("status", "generated");
            result.put("s3Bucket", reportS3Bucket);
            result.put("s3Key", s3Key);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a report download URL by retrieving the environment-specific base URL
     * from AWS Systems Manager Parameter Store and appending the report name.
     *
     * cr-java-0071 FIX: The former hard-coded URL
     * "http://reports.resorts-internal.com:8080/download/" is replaced with a value
     * fetched at runtime from SSM Parameter Store. Each deployment environment stores
     * its own base URL in the parameter identified by {@code reportDownloadUrlParam},
     * enabling environment-agnostic deployments without code changes.
     *
     * @param reportName the report file name to append to the base URL
     * @return the full report download URL constructed from the SSM-stored base URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // cr-java-0071 FIX: Retrieve the environment-specific report download base URL
        // from AWS Systems Manager Parameter Store instead of using a hard-coded value.
        // The SSM parameter name is injected via report.download.url.param (or the
        // REPORT_DOWNLOAD_URL_PARAM environment variable), making this method
        // environment-agnostic across dev, staging, and production deployments.
        String reportBaseUrl = getParameterFromSsm(reportDownloadUrlParam);
        return reportBaseUrl + reportName;
    }

    /**
     * Returns system information using cloud-native S3 paths instead of
     * local file system paths.
     * cr-java-0111 FIX: Timestamp is now generated using java.time.Instant (UTC) and
     * DateTimeFormatter instead of the legacy java.util.Date / SimpleDateFormat API,
     * eliminating server-local timezone dependencies in distributed cloud environments.
     *
     * @return a map containing S3 bucket/prefix info and a generation timestamp
     */
    public Map<String, Object> getSystemInfo() {
        // cr-java-0111 FIX: Use java.time.Instant (UTC) + DateTimeFormatter instead of
        // java.util.Date / SimpleDateFormat to avoid server-local timezone dependencies.
        String timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        Map<String, Object> info = new HashMap<>();
        // Replace hardcoded local paths with S3 bucket/prefix references
        info.put("reportS3Bucket", reportS3Bucket);
        info.put("reportS3Prefix", reportS3Prefix);
        info.put("backupS3Prefix", backupS3Prefix);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Builds and returns an {@link S3Client} configured for the injected AWS region.
     * Credentials are resolved automatically via the AWS Default Credential Provider Chain
     * (IAM role, environment variables, ~/.aws/credentials, etc.).
     *
     * @return a configured {@link S3Client} instance
     */
    private S3Client buildS3Client() {
        return S3Client.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Retrieves a plaintext parameter value from AWS Systems Manager Parameter Store.
     * Credentials are resolved via the AWS Default Credential Provider Chain
     * (IAM role, environment variables, ~/.aws/credentials, etc.).
     *
     * @param parameterName the SSM parameter name (e.g. "/resortslite/report-download-url")
     * @return the parameter value stored in SSM Parameter Store
     */
    private String getParameterFromSsm(String parameterName) {
        try (SsmClient ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build()) {

            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(false)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        }
    }
}
