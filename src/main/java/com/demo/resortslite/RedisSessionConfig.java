package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * cr-java-0065 — HTTP Session State Storage remediation.
 * cr-java-0067 — In-Memory Caching Without TTL remediation.
 *
 * Replaces in-process {@link javax.servlet.http.HttpSession} storage and the
 * unbounded static {@code HashMap} booking cache with distributed, Redis-backed
 * storage provided by Amazon ElastiCache.
 *
 * <p>Spring Session intercepts every {@code HttpSession} read/write in the
 * application (including those in {@link BookingController}) and transparently
 * delegates them to ElastiCache.  No changes to the controller's session API
 * calls are required — the swap is entirely infrastructure-level.</p>
 *
 * <p>The {@link RedisTemplate} bean defined here is used by {@link BookingController}
 * to cache booking objects in ElastiCache with a configurable TTL, replacing the
 * previous unbounded in-memory {@code HashMap} that caused memory growth and
 * stale data inconsistencies across horizontally-scaled instances.</p>
 *
 * <p>Benefits for AWS cloud deployment:</p>
 * <ul>
 *   <li>Session data is stored centrally in ElastiCache, not in JVM heap.</li>
 *   <li>Any EC2 / ECS instance can serve any request — no server affinity.</li>
 *   <li>Auto-scaling and instance replacement no longer cause session loss.</li>
 *   <li>AWS ALB can distribute traffic freely across all healthy instances.</li>
 *   <li>Booking cache entries expire automatically via TTL — no memory leaks.</li>
 *   <li>All instances share a consistent, centralized cache view.</li>
 * </ul>
 *
 * <p>Required environment variables (set via ECS task definition, EKS pod spec,
 * or Elastic Beanstalk environment properties):</p>
 * <pre>
 *   REDIS_HOST              — ElastiCache primary endpoint (default: localhost)
 *   REDIS_PORT              — ElastiCache port             (default: 6379)
 *   SESSION_TIMEOUT_SEC     — Max inactive interval in seconds (default: 1800)
 *   BOOKING_CACHE_TTL_MINUTES — Booking cache TTL in minutes (default: 30)
 * </pre>
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    /**
     * ElastiCache primary endpoint hostname.
     * Set via environment variable {@code REDIS_HOST} or Spring property
     * {@code spring.redis.host}.  In production, this should point to the
     * ElastiCache cluster primary endpoint (e.g.,
     * {@code my-cluster.abc123.ng.0001.use1.cache.amazonaws.com}).
     */
    @Value("${spring.redis.host:${REDIS_HOST:localhost}}")
    private String redisHost;

    /**
     * ElastiCache port (default 6379 for Redis).
     * Set via environment variable {@code REDIS_PORT} or Spring property
     * {@code spring.redis.port}.
     */
    @Value("${spring.redis.port:${REDIS_PORT:6379}}")
    private int redisPort;

    /**
     * Lettuce-based Redis connection factory pointing to Amazon ElastiCache.
     *
     * <p>Lettuce is the recommended client for ElastiCache because it supports
     * TLS (in-transit encryption) and cluster mode out of the box.  For
     * production deployments enable TLS by setting
     * {@code spring.redis.ssl=true} and pointing to the ElastiCache TLS port
     * (6380).</p>
     *
     * @return a configured {@link LettuceConnectionFactory}
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * cr-java-0067 FIX: General-purpose {@link RedisTemplate} for application-level
     * caching in Amazon ElastiCache for Redis.
     *
     * <p>Used by {@link BookingController} to store booking objects with a TTL,
     * replacing the previous unbounded static {@code HashMap} that caused:</p>
     * <ul>
     *   <li>Indefinite memory growth (no eviction policy)</li>
     *   <li>Stale data inconsistencies across multiple instances</li>
     *   <li>Cache state invisible to other EC2/ECS nodes in the cluster</li>
     * </ul>
     *
     * <p>Serialization strategy:</p>
     * <ul>
     *   <li>Keys: {@link StringRedisSerializer} — human-readable key names in Redis</li>
     *   <li>Values: {@link GenericJackson2JsonRedisSerializer} — JSON serialization
     *       for type-safe deserialization of cached objects</li>
     * </ul>
     *
     * @return a configured {@link RedisTemplate} with String keys and Object values
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate() {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory());
        // Use StringRedisSerializer for keys to produce readable Redis key names
        // (e.g., "resortslite:booking:BK-A1B2C3D4")
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Use GenericJackson2JsonRedisSerializer for values to enable type-safe
        // JSON serialization/deserialization of cached booking Map objects
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
