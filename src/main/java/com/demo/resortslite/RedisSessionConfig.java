package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * RedisSessionConfig — cr-java-0065 FIX
 *
 * Configures Amazon ElastiCache for Redis as the centralised HTTP session store
 * using Spring Session Data Redis.  All session state previously held in
 * javax.servlet.http.HttpSession is now stored in Redis, making every
 * application instance fully stateless and enabling horizontal scaling behind
 * an AWS Application Load Balancer without sticky sessions.
 *
 * Connection details are supplied via environment variables (or application
 * properties) so that the same artefact can be deployed to any environment
 * (dev / staging / production) without code changes:
 *
 *   REDIS_HOST  — ElastiCache primary endpoint hostname  (default: localhost)
 *   REDIS_PORT  — ElastiCache port                       (default: 6379)
 *
 * The @EnableRedisHttpSession annotation activates Spring Session's Redis-backed
 * HttpSession replacement.  maxInactiveIntervalInSeconds controls the global
 * session TTL and can be tuned via the session.ttl.minutes property.
 */
@Configuration
@EnableRedisHttpSession(maxInactiveIntervalInSeconds = 1800) // 30 minutes default
public class RedisSessionConfig {

    /**
     * ElastiCache primary endpoint hostname.
     * Set the REDIS_HOST environment variable in the ECS task definition,
     * EKS pod spec, or Elastic Beanstalk environment configuration.
     */
    @Value("${spring.redis.host:${REDIS_HOST:localhost}}")
    private String redisHost;

    /**
     * ElastiCache port (default 6379).
     * Set the REDIS_PORT environment variable to override.
     */
    @Value("${spring.redis.port:${REDIS_PORT:6379}}")
    private int redisPort;

    /**
     * Lettuce-based Redis connection factory pointing at the ElastiCache cluster.
     * Lettuce is the default client bundled with spring-boot-starter-data-redis
     * and supports both standalone and cluster modes.
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        return new LettuceConnectionFactory(config);
    }

    /**
     * RedisTemplate configured with:
     *   - StringRedisSerializer for keys   (human-readable key names in Redis)
     *   - GenericJackson2JsonRedisSerializer for values (JSON serialisation of
     *     arbitrary Java objects, including booking Maps)
     *
     * This template is injected into BookingController to store and retrieve
     * session-scoped data (lastBooking, guestName) in ElastiCache.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
