package com.demo.resortslite;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Distributed cache service using Redis.
 * Replaces local in-memory caches for horizontal scalability.
 */
@Service
public class DistributedCacheService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * Store value in distributed cache
     * @param key Cache key
     * @param value Value to cache
     * @param ttlMinutes Time to live in minutes
     */
    public void put(String key, Object value, long ttlMinutes) {
        redisTemplate.opsForValue().set(key, value, ttlMinutes, TimeUnit.MINUTES);
    }

    /**
     * Store value in distributed cache with default TTL of 60 minutes
     * @param key Cache key
     * @param value Value to cache
     */
    public void put(String key, Object value) {
        put(key, value, 60);
    }

    /**
     * Retrieve value from distributed cache
     * @param key Cache key
     * @return Cached value or null if not found
     */
    public Object get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * Remove value from distributed cache
     * @param key Cache key
     */
    public void remove(String key) {
        redisTemplate.delete(key);
    }

    /**
     * Check if key exists in cache
     * @param key Cache key
     * @return true if key exists
     */
    public boolean exists(String key) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }
}
