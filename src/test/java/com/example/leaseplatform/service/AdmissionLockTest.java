package com.example.leaseplatform.service;

import com.example.leaseplatform.exception.ApiException;
import com.example.leaseplatform.config.BookingSettings;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.*;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdmissionLockTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final AdmissionLock admission = new AdmissionLock(redis,
            new BookingSettings(Duration.ofMinutes(15), true, Duration.ofSeconds(5), true));

    @Test
    void databaseOnlyModeNeverAccessesRedis() {
        try (var ignored = admission.acquire(UUID.randomUUID(), false)) { verifyNoInteractions(redis); }
        verifyNoInteractions(redis);
    }

    @Test
    void contentionReturnsATransientResponse() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThatThrownBy(() -> admission.acquire(UUID.randomUUID(), true))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(503);
                    assertThat(error.code()).isEqualTo("LOCK_BUSY");
                });
    }

    @Test
    void unavailableRedisFailsClosed() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("unavailable"));
        assertThatThrownBy(() -> admission.acquire(UUID.randomUUID(), true))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("REDIS_UNAVAILABLE"));
    }
}
