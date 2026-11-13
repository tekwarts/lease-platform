package com.example.leaseplatform.service;

import com.example.leaseplatform.exception.ApiException;
import com.example.leaseplatform.config.BookingSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

@Component
public class AdmissionLock {
    private static final Logger log = LoggerFactory.getLogger(AdmissionLock.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;
    private final BookingSettings settings;

    public AdmissionLock(StringRedisTemplate redis, BookingSettings settings) {
        this.redis = redis;
        this.settings = settings;
    }

    public Ticket acquire(UUID propertyId, boolean enabled) {
        if (!enabled)
            return () -> {
            };
        String key = "lock:property:" + propertyId;
        String token = UUID.randomUUID().toString();
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, settings.redisLockTtl()))) {
                throw new ApiException(503, "LOCK_BUSY",
                        "Another reservation is being processed. Retry with the same key.");
            }
        } catch (DataAccessException error) {
            throw new ApiException(503, "REDIS_UNAVAILABLE",
                    "Redis unavailable. Admission is configured to fail closed.");
        }
        return () -> {
            try {
                redis.execute(RELEASE, List.of(key), token);
            } catch (DataAccessException error) {
                // A release failure after commit must not replace a successful HTTP response.
                // The token's TTL bounds the remaining admission delay.
                log.warn("Could not release admission lock for property {}; waiting for its TTL", propertyId);
            }
        };
    }

    public interface Ticket extends AutoCloseable {
        @Override
        void close();
    }
}
