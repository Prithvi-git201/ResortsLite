package com.demo.resortslite;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Google Cloud Platform configuration
 * FIXED cr-java-0061, cr-java-0062, cr-java-0063: Configures Google Cloud Storage client
 * FIXED cr-java-0069: Enables Google Secret Manager integration via Spring Cloud GCP
 */
@Configuration
public class GcpConfig {

    @Value("${gcp.storage.project-id:}")
    private String projectId;

    /**
     * Google Cloud Storage client bean
     * Used for all file operations to replace local filesystem dependencies
     */
    @Bean
    public Storage googleCloudStorage() {
        StorageOptions.Builder builder = StorageOptions.newBuilder();
        
        if (projectId != null && !projectId.isEmpty()) {
            builder.setProjectId(projectId);
        }
        
        return builder.build().getService();
    }
}
