package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * RedisSessionConfig — Spring Session / ElastiCache configuration.
 *
 * <p><strong>cr-java-0065 FIX:</strong>
 * Activates Spring Session Data Redis so that every {@link javax.servlet.http.HttpSession}
 * in the application is transparently backed by <strong>Amazon ElastiCache for Redis</strong>
 * instead of the servlet container's in-memory store.  This eliminates server affinity
 * (sticky sessions) and allows the application to scale horizontally across multiple EC2
 * instances behind an AWS Application Load Balancer without any session data loss.</p>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Spring Session replaces the default {@code HttpSession} implementation with a
 *       Redis-backed one at the servlet filter level — no application code changes are
 *       required beyond adding this configuration class.</li>
 *   <li>Session data is serialised to the ElastiCache cluster identified by
 *       {@code REDIS_HOST} / {@code REDIS_PORT} environment variables (with safe
 *       defaults for local development).</li>
 *   <li>The {@code maxInactiveIntervalInSeconds} parameter controls the Redis TTL for
 *       each session entry, preventing unbounded memory growth in the cache.</li>
 * </ol>
 *
 * <h3>Required environment variables (set in ECS task definition / Elastic Beanstalk)</h3>
 * <pre>
 *   REDIS_HOST  — ElastiCache primary endpoint  (default: localhost)
 *   REDIS_PORT  — ElastiCache port              (default: 6379)
 * </pre>
 *
 * <h3>Required application.properties entries (already added)</h3>
 * <pre>
 *   spring.redis.host=${REDIS_HOST:localhost}
 *   spring.redis.port=${REDIS_PORT:6379}
 *   server.servlet.session.timeout=1800
 * </pre>
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    /**
     * ElastiCache primary endpoint hostname.
     * Injected from the {@code REDIS_HOST} environment variable;
     * falls back to {@code localhost} for local development.
     */
    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    /**
     * ElastiCache port.
     * Injected from the {@code REDIS_PORT} environment variable;
     * falls back to {@code 6379} for local development.
     */
    @Value("${spring.redis.port:6379}")
    private int redisPort;

    /**
     * Creates a Lettuce-based {@link LettuceConnectionFactory} pointing at the
     * ElastiCache cluster.  Lettuce is the recommended non-blocking Redis client
     * for Spring Boot 2.x and is included transitively via
     * {@code spring-boot-starter-data-redis}.
     *
     * @return a configured {@link LettuceConnectionFactory}
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }
}
