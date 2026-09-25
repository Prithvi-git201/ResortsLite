package com.demo.resortslite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.beans.factory.annotation.Value;

/**
 * RedisSessionConfig — Spring Session Data Redis configuration.
 *
 * <p><strong>cr-java-0065 FIX — HTTP Session State Storage:</strong><br>
 * This configuration class activates Amazon ElastiCache for Redis as the
 * centralised, distributed HTTP session store for the ResortsLite application.
 *
 * <p>By annotating with {@link EnableRedisHttpSession}, Spring Session intercepts
 * every {@code HttpSession.setAttribute} / {@code getAttribute} call made in
 * {@code BookingController} and transparently serialises the data to the Redis
 * cluster instead of keeping it in JVM heap memory.  This eliminates server
 * affinity: any EC2 / ECS instance can serve any request because session data
 * is stored externally in ElastiCache, not on the originating instance.
 *
 * <p>Session serialisation uses {@link GenericJackson2JsonRedisSerializer} so
 * that session payloads are human-readable JSON in Redis, simplifying debugging
 * and monitoring via AWS ElastiCache console or redis-cli.
 *
 * <p>Connection parameters are resolved from environment variables
 * ({@code REDIS_HOST}, {@code REDIS_PORT}) which should be supplied by the ECS
 * task definition or EC2 user-data, sourced from AWS Systems Manager Parameter
 * Store at deployment time.
 *
 * <p>Session timeout is controlled by the {@code maxInactiveIntervalInSeconds}
 * attribute of {@link EnableRedisHttpSession} (default: 1800 s = 30 minutes).
 * Override via the {@code SESSION_TIMEOUT_SECONDS} environment variable.
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    /**
     * Redis host — resolved from the {@code REDIS_HOST} environment variable or
     * AWS Systems Manager Parameter Store ({@code /resortslite/redis/host}).
     * Defaults to {@code localhost} for local development.
     */
    @Value("${spring.redis.host:localhost}")
    private String redisHost;

    /**
     * Redis port — resolved from the {@code REDIS_PORT} environment variable.
     * Defaults to {@code 6379}.
     */
    @Value("${spring.redis.port:6379}")
    private int redisPort;

    /**
     * Creates the Lettuce-based Redis connection factory pointing at the
     * Amazon ElastiCache primary endpoint.
     *
     * @return a {@link LettuceConnectionFactory} configured with the host and
     *         port supplied at runtime via environment variables
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(redisHost, redisPort);
    }

    /**
     * Configures JSON serialisation for Spring Session Redis entries.
     *
     * <p>Using {@link GenericJackson2JsonRedisSerializer} ensures that session
     * objects (e.g. booking maps stored by {@code BookingController}) are
     * serialised as JSON rather than Java binary format, making them portable
     * across JVM restarts and readable in the ElastiCache console.
     *
     * @return a {@link RedisSerializer} that serialises session data as JSON
     */
    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        return new GenericJackson2JsonRedisSerializer();
    }
}
