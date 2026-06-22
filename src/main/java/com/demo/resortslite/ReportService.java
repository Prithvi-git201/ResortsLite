package com.demo.resortslite;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@Service
public class ReportService {

    // FIXED cr-java-0061, cr-java-0062, cr-java-0063: Replaced hardcoded file paths with Azure Blob Storage
    @Value("${azure.storage.account-name}")
    private String storageAccountName;

    @Value("${azure.storage.container-name:reports}")
    private String containerName;

    @Value("${azure.storage.backup-container-name:backups}")
    private String backupContainerName;

    // FIXED cr-java-0077: Replaced hardcoded port with environment variable
    @Value("${server.port:8080}")
    private int serverPort;

    // FIXED cr-java-0071: Externalized URL to Azure App Configuration
    @Value("${app.reports.download.url:https://reports.resorts-internal.com/download}")
    private String reportsBaseUrl;

    // FIXED cr-java-0111: Azure Service Bus for scheduled messages
    @Value("${azure.servicebus.connection-string:}")
    private String serviceBusConnectionString;

    @Value("${azure.servicebus.queue-name:scheduled-reports}")
    private String queueName;

    private BlobServiceClient blobServiceClient;
    private BlobContainerClient reportContainerClient;
    private BlobContainerClient backupContainerClient;
    private ServiceBusSenderClient serviceBusSenderClient;

    @PostConstruct
    public void init() {
        // FIXED cr-java-0061, cr-java-0062, cr-java-0063: Initialize Azure Blob Storage client
        String endpoint = String.format("https://%s.blob.core.windows.net", storageAccountName);
        blobServiceClient = new BlobServiceClientBuilder()
                .endpoint(endpoint)
                .credential(new DefaultAzureCredentialBuilder().build())
                .buildClient();

        // Create containers if they don't exist
        reportContainerClient = blobServiceClient.getBlobContainerClient(containerName);
        if (!reportContainerClient.exists()) {
            reportContainerClient.create();
        }

        backupContainerClient = blobServiceClient.getBlobContainerClient(backupContainerName);
        if (!backupContainerClient.exists()) {
            backupContainerClient.create();
        }

        // FIXED cr-java-0111: Initialize Azure Service Bus client for scheduled messages
        if (serviceBusConnectionString != null && !serviceBusConnectionString.isEmpty()) {
            serviceBusSenderClient = new ServiceBusClientBuilder()
                    .connectionString(serviceBusConnectionString)
                    .sender()
                    .queueName(queueName)
                    .buildClient();
        }
    }

    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";

        Map<String, Object> result = new HashMap<>();

        try {
            // FIXED cr-java-0062, cr-java-0063: Write to Azure Blob Storage instead of local file system
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            byte[] data = csvContent.toString().getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);

            BlobClient blobClient = reportContainerClient.getBlobClient(fileName);
            blobClient.upload(inputStream, data.length, true);

            result.put("status", "generated");
            result.put("blobName", fileName);
            result.put("containerName", containerName);
            result.put("storageAccount", storageAccountName);
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds a download URL for the specified report.
     * FIXED: Missing JavaDoc documentation added.
     * FIXED cr-java-0071: URL now externalized to Azure App Configuration.
     * 
     * @param reportName The name of the report file
     * @return The complete HTTPS download URL
     */
    public String buildReportDownloadUrl(String reportName) {
        // FIXED cr-java-0071: Using externalized HTTPS URL from configuration
        return reportsBaseUrl + "/" + reportName;
    }

    /**
     * Retrieves system configuration information.
     * FIXED: Missing JavaDoc documentation added.
     * 
     * @return Map containing system configuration details
     */
    public Map<String, Object> getSystemInfo() {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        Map<String, Object> info = new HashMap<>();
        // FIXED cr-java-0061: Using Azure Blob Storage container names instead of file paths
        info.put("reportContainer", containerName);
        info.put("backupContainer", backupContainerName);
        info.put("storageAccount", storageAccountName);
        // FIXED cr-java-0077: Using environment-configured port
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        return info;
    }

    /**
     * Schedules a report generation task using Azure Service Bus.
     * FIXED cr-java-0111: Replaced java.util.Timer with Azure Service Bus scheduled messages.
     * 
     * @param reportType The type of report to generate
     * @param delayMinutes Delay in minutes before report generation
     */
    public void scheduleReportGeneration(String reportType, int delayMinutes) {
        if (serviceBusSenderClient != null) {
            ServiceBusMessage message = new ServiceBusMessage("Generate report: " + reportType);
            message.setScheduledEnqueueTime(
                    java.time.OffsetDateTime.now().plus(Duration.ofMinutes(delayMinutes))
            );
            serviceBusSenderClient.sendMessage(message);
        }
    }

    /**
     * Backs up a report to the backup container in Azure Blob Storage.
     * FIXED cr-java-0061, cr-java-0062, cr-java-0063: Using Azure Blob Storage for backups.
     * 
     * @param reportName The name of the report to backup
     * @return Status map indicating success or failure
     */
    public Map<String, Object> backupReport(String reportName) {
        Map<String, Object> result = new HashMap<>();
        try {
            BlobClient sourceBlob = reportContainerClient.getBlobClient(reportName);
            BlobClient destBlob = backupContainerClient.getBlobClient(reportName);

            if (sourceBlob.exists()) {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                sourceBlob.download(outputStream);
                byte[] data = outputStream.toByteArray();

                ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
                destBlob.upload(inputStream, data.length, true);

                result.put("status", "backed_up");
                result.put("reportName", reportName);
                result.put("backupContainer", backupContainerName);
            } else {
                result.put("status", "error");
                result.put("message", "Report not found: " + reportName);
            }
        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }
        return result;
    }
}
