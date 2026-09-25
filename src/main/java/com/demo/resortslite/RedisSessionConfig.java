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
 * cr-java-0065 FIX: Distributed HTTP Session via Amazon ElastiCache for Redis.
 *
 * <p>Replaces the default in-process {@link javax.servlet.http.HttpSession} storage with
 * a Redis-backed session store managed by Spring Session. All session attributes
 * (e.g., "lastBooking", "guestName") are serialised and persisted in ElastiCache so
 * that every application instance in the Auto Scaling Group shares the same session
 * data. This eliminates server affinity, prevents data loss on instance termination,
 * and allows the AWS ALB to route requests to any healthy instance without sticky
 * sessions.</p>
 *
 * <p>cr-java-0067 FIX: A {@link RedisTemplate} bean is provided here for use by
 * {@link BookingController} to replace the former unbounded static in-memory HashMap
 * (bookingCache). The template uses {@link GenericJackson2JsonRedisSerializer} for
 * values so that booking objects are stored as human-readable JSON in Redis, and
 * {@link StringRedisSerializer} for keys. TTL is applied at the call site via
 * {@code opsForValue().set(key, value, ttl, TimeUnit)} to ensure controlled expiration
 * and prevent indefinite memory growth.</p>
 *
 * <p>Configuration is fully externalised via environment variables / application
 * properties — no credentials or hostnames are hard-coded in source code.</p>
 *
 * <ul>
 *   <li>{@code REDIS_HOST} / {@code spring.redis.host} — ElastiCache primary endpoint</li>
 *   <li>{@code REDIS_PORT} / {@code spring.redis.port} — ElastiCache port (default 6379)</li>
 *   <li>{@code SESSION_TIMEOUT_SECONDS} — session TTL in seconds (default 1800 = 30 min)</li>
 *   <li>{@code CACHE_BOOKING_TTL_SECONDS} / {@code cache.booking.ttl-seconds} — booking
 *       cache entry TTL in seconds (default 3600 = 1 hour)</li>
 * </ul>
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800)
public class RedisSessionConfig {

    /** ElastiCache primary endpoint — injected from environment / application.properties. */
    @Value("${spring.redis.host:${REDIS_HOST:localhost}}")
    private String redisHost;

    /** ElastiCache port — injected from environment / application.properties. */
    @Value("${spring.redis.port:${REDIS_PORT:6379}}")
    private int redisPort;

    /**
     * Lettuce connection factory pointing at the Amazon ElastiCache Redis cluster.
     *
     * <p>In production the {@code spring.redis.host} property is set to the ElastiCache
     * primary endpoint DNS name via an ECS task-definition environment variable or an
     * AWS Systems Manager Parameter Store value. In local development it falls back to
     * {@code localhost} so that a local Redis instance can be used without any code
     * change.</p>
     *
     * @return a configured {@link LettuceConnectionFactory}
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * cr-java-0067 FIX: RedisTemplate for booking cache operations with TTL support.
     *
     * <p>Replaces the former unbounded static {@code HashMap<String, Object> bookingCache}
     * in {@link BookingController}. This template is used to store and retrieve booking
     * objects in Amazon ElastiCache for Redis with an explicit TTL, ensuring:</p>
     * <ul>
     *   <li>Controlled expiration — entries expire after {@code cache.booking.ttl-seconds}
     *       (default 3600 s / 1 hour), preventing indefinite memory growth.</li>
     *   <li>Shared cache — all Auto Scaling Group instances read from and write to the
     *       same ElastiCache cluster, eliminating instance-local stale data.</li>
     *   <li>JSON serialization — booking objects are stored as JSON via
     *       {@link GenericJackson2JsonRedisSerializer}, making them inspectable and
     *       portable across JVM restarts.</li>
     *   <li>String keys — {@link StringRedisSerializer} ensures human-readable Redis keys
     *       (e.g., {@code booking:BK-A1B2C3D4}) for easy debugging and monitoring.</li>
     * </ul>
     *
     * @return a configured {@link RedisTemplate} for {@code String} keys and
     *         {@code Object} values
     */
    @Bean
    public RedisTemplate<String, Object> bookingRedisTemplate() {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory());

        // Use StringRedisSerializer for keys — produces readable keys like "booking:BK-XXXXXXXX"
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Use GenericJackson2JsonRedisSerializer for values — stores booking Maps as JSON.
        // This ensures type information is preserved across JVM restarts and is compatible
        // with other consumers (e.g., monitoring dashboards, cache inspection tools).
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
