package com.example.leaseplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("leaseflow")
public record BookingSettings(Duration holdDuration, boolean redisEnabled, Duration redisLockTtl, boolean demoEnabled) {
    public BookingSettings {
        if (holdDuration == null || holdDuration.isZero() || holdDuration.isNegative()) {
            throw new IllegalArgumentException("leaseflow.hold-duration must be positive");
        }
        if (redisLockTtl == null || redisLockTtl.isZero() || redisLockTtl.isNegative()) {
            throw new IllegalArgumentException("leaseflow.redis-lock-ttl must be positive");
        }
    }
}
