package com.demo.resortslite;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * cz-java-0070: Redis configuration for Azure Cache for Redis on AKS.
 *
 * Provides a RedisTemplate bean used by BookingController to store and retrieve
 * booking entries in a distributed cache shared across all AKS pod replicas.
 * This replaces the former local in-memory HashMap (bookingCache) that was
 * instance-local and invisible to other pods, breaking horizontal scaling.
 *
 * Connection details are injected from Azure Key Vault via the Secrets Store
 * CSI Driver on AKS using the following environment variables:
 *   REDIS_HOST     — Azure Cache for Redis hostname
 *   REDIS_PORT     — Redis port (default 6380 for SSL on Azure)
 *   REDIS_PASSWORD — Azure Cache for Redis access key
 *
 * These are mapped in application.properties:
 *   spring.redis.host=${REDIS_HOST:localhost}
 *   spring.redis.port=${REDIS_PORT:6379}
 *   spring.redis.password=${REDIS_PASSWORD:}
 *   spring.redis.ssl=${REDIS_SSL:true}
 */
@Configuration
public class RedisConfig {

    /**
     * Configures a RedisTemplate with String keys and JSON-serialised Object values.
     * Keys are serialised as plain strings (e.g. "booking:BK-XXXXXXXX").
     * Values are serialised as JSON using GenericJackson2JsonRedisSerializer so that
     * booking Map objects are stored in a human-readable, type-safe format.
     *
     * @param connectionFactory auto-configured by spring-boot-starter-data-redis
     *                          using the REDIS_HOST / REDIS_PORT / REDIS_PASSWORD
     *                          environment variables.
     * @return configured RedisTemplate for distributed booking cache operations.
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
