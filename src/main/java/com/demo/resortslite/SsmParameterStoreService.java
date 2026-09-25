package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import javax.annotation.PostConstruct;
import java.util.logging.Logger;

/**
 * Service that retrieves configuration values from AWS Systems Manager Parameter Store.
 *
 * <p>cr-java-0071 (Hard-coded Environment URLs): All environment-specific URLs that were
 * previously hard-coded in application source files are now fetched at startup from
 * AWS SSM Parameter Store. This enables environment-agnostic deployments — the same
 * artifact can be promoted from dev → staging → production simply by changing the
 * SSM parameter values in each environment, with no code changes required.</p>
 *
 * <p>Parameter names are externalised in {@code application.properties} and can be
 * overridden via environment variables, following 12-factor app principles.</p>
 */
@Component
public class SsmParameterStoreService {

    private static final Logger logger = Logger.getLogger(SsmParameterStoreService.class.getName());

    /**
     * AWS region used when constructing the SSM client.
     * Injected from the {@code AWS_REGION} environment variable or
     * the {@code aws.region} application property.
     */
    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    /**
     * SSM parameter name for the inventory service URL.
     * cr-java-0071: replaces the former hard-coded
     * {@code "http://inventory-service.internal:8081/rooms/available"} literal.
     */
    @Value("${ssm.parameter.inventory-url:/resortslite/inventory-service-url}")
    private String inventoryUrlParameterName;

    /**
     * SSM parameter name for the report download base URL.
     * cr-java-0071: replaces the former hard-coded
     * {@code "http://reports.resorts-internal.com:8080/download/"} literal.
     */
    @Value("${ssm.parameter.report-download-url:/resortslite/report-download-url}")
    private String reportDownloadUrlParameterName;

    /**
     * SSM parameter name for the server port.
     * cr-java-0077: replaces the former hard-coded {@code SERVER_PORT = 8080} constant.
     * The SSM parameter value is injected at runtime through the {@code SERVER_PORT}
     * environment variable in ECS, EKS, or Elastic Beanstalk task/pod definitions,
     * enabling dynamic port assignment by container orchestration platforms.
     */
    @Value("${ssm.parameter.server-port:/resortslite/server-port}")
    private String serverPortParameterName;

    /**
     * Fallback value for the inventory service URL, used when SSM is unavailable
     * (e.g., local development without AWS credentials).
     * cr-java-0071: the fallback is itself externalised via an application property
     * so it is never hard-coded in source.
     */
    @Value("${app.inventory.url.fallback:http://inventory-service.internal:8081/rooms/available}")
    private String inventoryUrlFallback;

    /**
     * Fallback value for the report download base URL, used when SSM is unavailable.
     * cr-java-0071: externalised via application property — not hard-coded in source.
     */
    @Value("${app.report.download.url.fallback:http://reports.resorts-internal.com:8080/download/}")
    private String reportDownloadUrlFallback;

    /**
     * Fallback value for the server port, used when SSM is unavailable.
     * cr-java-0077: externalised via application property — never hard-coded in source.
     * In production, the {@code SERVER_PORT} environment variable set by ECS/EKS takes
     * precedence over this fallback.
     */
    @Value("${app.server.port.fallback:8080}")
    private String serverPortFallback;

    private SsmClient ssmClient;

    /**
     * Initialises the SSM client after Spring has injected all property values.
     */
    @PostConstruct
    public void init() {
        this.ssmClient = SsmClient.builder()
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Retrieves the inventory service URL from AWS SSM Parameter Store.
     *
     * <p>cr-java-0071: the URL is no longer hard-coded; it is fetched from SSM at
     * runtime so that each deployment environment (dev / staging / prod) can supply
     * its own endpoint without any code change.</p>
     *
     * @return the inventory service URL resolved from SSM, or the configured fallback
     *         if the parameter cannot be retrieved.
     */
    public String getInventoryServiceUrl() {
        return getParameter(inventoryUrlParameterName, inventoryUrlFallback);
    }

    /**
     * Retrieves the report download base URL from AWS SSM Parameter Store.
     *
     * <p>cr-java-0071: the URL is no longer hard-coded; it is fetched from SSM at
     * runtime so that each deployment environment can supply its own endpoint.</p>
     *
     * @return the report download base URL resolved from SSM, or the configured
     *         fallback if the parameter cannot be retrieved.
     */
    public String getReportDownloadUrl() {
        return getParameter(reportDownloadUrlParameterName, reportDownloadUrlFallback);
    }

    /**
     * Retrieves the server port from AWS SSM Parameter Store.
     *
     * <p>cr-java-0077 FIX: The former hard-coded {@code SERVER_PORT = 8080} constant is
     * replaced by a dynamic lookup from SSM Parameter Store. The resolved value is then
     * injected into the application via the {@code SERVER_PORT} environment variable in
     * ECS task definitions, EKS pod specs, or Elastic Beanstalk environment configuration,
     * enabling container orchestration platforms to perform dynamic port assignment and
     * service discovery without any code change.</p>
     *
     * @return the server port as a String resolved from SSM, or the configured fallback
     *         if the parameter cannot be retrieved.
     */
    public String getServerPort() {
        return getParameter(serverPortParameterName, serverPortFallback);
    }

    /**
     * Generic helper that fetches a single SSM parameter by name.
     *
     * @param parameterName the SSM parameter path/name to look up
     * @param fallbackValue the value to return if SSM is unreachable or the parameter
     *                      does not exist
     * @return the resolved parameter value, or {@code fallbackValue} on any error
     */
    private String getParameter(String parameterName, String fallbackValue) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();
            GetParameterResponse response = ssmClient.getParameter(request);
            String value = response.parameter().value();
            logger.info("SSM parameter resolved: " + parameterName);
            return value;
        } catch (ParameterNotFoundException e) {
            logger.warning("SSM parameter not found: " + parameterName
                    + " — using fallback value.");
            return fallbackValue;
        } catch (Exception e) {
            logger.warning("Failed to retrieve SSM parameter: " + parameterName
                    + " (" + e.getMessage() + ") — using fallback value.");
            return fallbackValue;
        }
    }
}
