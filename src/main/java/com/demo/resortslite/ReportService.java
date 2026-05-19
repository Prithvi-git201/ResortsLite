package com.demo.resortslite;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * ReportService - Cloud-ready report generation service using Azure Blob Storage.
 * 
 * FIXED VIOLATIONS:
 * - cr-java-0061: Replaced hard-coded file paths with Azure Blob Storage
 * - cr-java-0062: Replaced local file writes with Azure Blob Storage
 * - cr-java-0063: Migrated java.io.File operations to Azure Blob Storage
 * - cr-java-0071: Externalized URLs to Azure App Configuration
 * - cr-java-0077: Replaced hard-coded ports with environment variables
 * - cr-java-0111: Replaced local timers with timezone-agnostic Instant
 */
@Service
public class ReportService {

    @Value("${azure.storage.account-name}")
    private String storageAccountName;

    @Value("${azure.storage.account-key}")
    private String storageAccountKey;

    @Value("${azure.storage.blob-endpoint}")
    private String blobEndpoint;

    @Value("${azure.storage.container-name}")
    private String containerName;

    @Value("${server.port}")
    private String serverPort;

    @Value("${app.payment.endpoint}")
    private String paymentEndpoint;

    private BlobServiceClient blobServiceClient;
    private BlobContainerClient containerClient;

    /**
     * Initializes Azure Blob Storage client.
     * Called lazily to avoid initialization issues if Azure credentials are not configured.
     */
    private void initializeBlobClient() {
        if (blobServiceClient == null) {
            String connectionString = String.format(
                "DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s;EndpointSuffix=core.windows.net",
                storageAccountName, storageAccountKey
            );
            blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildClient();
            containerClient = blobServiceClient.getBlobContainerClient(containerName);
            
            // Create container if it doesn't exist
            if (!containerClient.exists()) {
                containerClient.create();
            }
        }
    }

    /**
     * Generates monthly report and stores it in Azure Blob Storage.
     * 
     * @param month The month for the report
     * @param year The year for the report
     * @return Map containing report generation status and blob URL
     */
    public Map<String, Object> generateMonthlyReport(String month, String year) {
        String fileName = "resort_report_" + month + "_" + year + ".csv";
        Map<String, Object> result = new HashMap<>();

        try {
            initializeBlobClient();

            // Generate CSV content
            StringBuilder csvContent = new StringBuilder();
            csvContent.append("BookingID,GuestName,RoomType,CheckIn,CheckOut,Amount\n");
            csvContent.append("BK-001,John Smith,SUITE,2024-03-01,2024-03-05,1750.00\n");
            csvContent.append("BK-002,Jane Doe,DELUXE,2024-03-03,2024-03-07,960.00\n");

            // Upload to Azure Blob Storage
            BlobClient blobClient = containerClient.getBlobClient(fileName);
            byte[] data = csvContent.toString().getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
            blobClient.upload(inputStream, data.length, true);

            result.put("status", "generated");
            result.put("blobUrl", blobClient.getBlobUrl());
            result.put("fileName", fileName);
            result.put("storageType", "Azure Blob Storage");
            result.put("serverPort", serverPort);

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", e.getMessage());
        }

        return result;
    }

    /**
     * Builds report download URL using externalized configuration.
     * 
     * @param reportName The name of the report
     * @return HTTPS URL for report download
     */
    public String buildReportDownloadUrl(String reportName) {
        // Use HTTPS and externalized endpoint from Azure App Configuration
        String baseUrl = blobEndpoint != null && !blobEndpoint.isEmpty() 
            ? blobEndpoint 
            : "https://" + storageAccountName + ".blob.core.windows.net";
        return baseUrl + "/" + containerName + "/" + reportName;
    }

    /**
     * Returns system information with cloud-native configuration.
     * 
     * @return Map containing system configuration details
     */
    public Map<String, Object> getSystemInfo() {
        // Use timezone-agnostic UTC timestamp (fixes cr-java-0111)
        String timestamp = DateTimeFormatter.ISO_INSTANT
            .format(Instant.now().atOffset(ZoneOffset.UTC));
        
        Map<String, Object> info = new HashMap<>();
        info.put("storageType", "Azure Blob Storage");
        info.put("containerName", containerName);
        info.put("storageAccount", storageAccountName);
        info.put("serverPort", serverPort);
        info.put("generatedAt", timestamp);
        info.put("timezone", "UTC");
        return info;
    }
}
