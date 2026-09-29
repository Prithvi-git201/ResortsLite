package com.demo.resortslite;

import net.spy.memcached.AddrUtil;
import net.spy.memcached.MemcachedClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * MemcachedConfig — Spring configuration for Amazon ElastiCache (Memcached).
 *
 * cz-java-0070 FIX — Local Caches:
 * The local in-memory HashMap cache (bookingCache) in BookingController has been replaced
 * with Amazon ElastiCache for Memcached to support horizontal scaling in ECS Fargate.
 * All container replicas share the same distributed Memcached cluster, eliminating the
 * instance-local cache that was invisible to other container instances.
 *
 * The Memcached cluster endpoint is injected via the MEMCACHED_ENDPOINT environment
 * variable, which ECS Fargate resolves from AWS SSM Parameter Store at task startup.
 * This ensures no endpoint is hardcoded and the configuration is environment-agnostic.
 *
 * ECS Task Definition — required environment variable (sourced from SSM Parameter Store):
 *   MEMCACHED_ENDPOINT = <elasticache-cluster-endpoint>:11211
 *
 * AWS SSM Parameter Store path (example):
 *   /resortslite/prod/memcached/endpoint  →  my-cluster.abc123.cfg.use1.cache.amazonaws.com:11211
 *
 * ECS Fargate task definition snippet (inject from SSM):
 *   {
 *     "name": "MEMCACHED_ENDPOINT",
 *     "valueFrom": "arn:aws:ssm:us-east-1:123456789012:parameter/resortslite/prod/memcached/endpoint"
 *   }
 */
@Configuration
public class MemcachedConfig {

    /**
     * ElastiCache Memcached cluster endpoint injected from the MEMCACHED_ENDPOINT
     * environment variable. ECS Fargate resolves this from AWS SSM Parameter Store
     * at task startup. Falls back to localhost:11211 for local development only.
     */
    @Value("${MEMCACHED_ENDPOINT:localhost:11211}")
    private String memcachedEndpoint;

    /**
     * Expiry time (in seconds) for cached booking entries.
     * Defaults to 3600 seconds (1 hour); override via MEMCACHED_EXPIRY_SECS env var.
     */
    @Value("${MEMCACHED_EXPIRY_SECS:3600}")
    private int memcachedExpirySecs;

    /**
     * Creates and exposes a {@link MemcachedClient} bean connected to the
     * ElastiCache Memcached cluster endpoint supplied via MEMCACHED_ENDPOINT.
     *
     * @return configured MemcachedClient
     * @throws IOException if the client cannot connect to the specified endpoint
     */
    @Bean(destroyMethod = "shutdown")
    public MemcachedClient memcachedClient() throws IOException {
        return new MemcachedClient(AddrUtil.getAddresses(memcachedEndpoint));
    }

    /**
     * Exposes the configured cache expiry duration so it can be injected into
     * components that interact with the Memcached client.
     *
     * @return cache entry TTL in seconds
     */
    @Bean(name = "memcachedExpirySecs")
    public int memcachedExpirySecs() {
        return memcachedExpirySecs;
    }
}
