package com.example.leaseplatform.service;

import com.example.leaseplatform.exception.ApiException;
import com.example.leaseplatform.config.BookingSettings;
import com.example.leaseplatform.domain.*;
import com.example.leaseplatform.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"leaseflow.demo-enabled=true", "leaseflow.expiration-interval=86400000"})
@Testcontainers
class ReservationConcurrencyIT {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.data.redis.host", REDIS::getHost);
        properties.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired ReservationService reservations;
    @Autowired ExpirationWorker expiration;
    @Autowired PropertyRepository properties;
    @Autowired LeaseRepository leases;
    @Autowired IdempotencyRequestRepository records;
    @Autowired TransactionTemplate transactions;
    @Autowired DatabaseTime clock;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired BookingSettings settings;
    @MockitoSpyBean AdmissionLock admission;

    private String owner() { return "test-" + UUID.randomUUID(); }

    private UUID property(String owner) {
        return transactions.execute(tx -> properties.saveAndFlush(Property.demo("Race property", clock.now(), owner, true)).getId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void thousandCompetingAttemptsCommitExactlyOneLiveLease(boolean redisEnabled) throws Exception {
        String owner = owner();
        UUID property = property(owner);
        var outcomes = race(1000, i -> attempt(owner, property, UUID.randomUUID(), redisEnabled));
        assertThat(outcomes.stream().filter(result -> result.equals("SUCCESS")).count()).isEqualTo(1);
        assertThat(outcomes).allMatch(result -> Set.of("SUCCESS", "PROPERTY_UNAVAILABLE", "OPTIMISTIC_CONFLICT",
                "DATABASE_CONFLICT", "LOCK_BUSY").contains(result));
        assertSingleOwner(property);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ?", Long.class, property)).isEqualTo(1L);
    }

    @Test
    void concurrentIdenticalKeysAndLostHttpResponseReplayOneCommittedResponse() throws Exception {
        String owner = owner();
        UUID property = property(owner), key = UUID.randomUUID();
        var bodies = race(100, i -> reservations.reserve(owner, owner, property, key, false).body());
        assertThat(new HashSet<>(bodies)).hasSize(1);
        var replay = reservations.reserve(owner, owner, property, key, false);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(bodies.getFirst());
        assertSingleOwner(property);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_request where user_id = ?", Long.class, owner)).isEqualTo(1L);
    }

    @Test
    void earlyExpiryOfEveryRedisTokenStillCannotDoubleBook() throws Exception {
        String owner = owner();
        UUID property = property(owner);
        doAnswer(invocation -> {
            AdmissionLock.Ticket ticket = (AdmissionLock.Ticket) invocation.callRealMethod();
            // Deterministically expire admission BEFORE entering the database transaction.
            // Other contenders can enter while this request is still processing.
            redis.expire("lock:property:" + invocation.getArgument(0), Duration.ZERO);
            return ticket;
        }).when(admission).acquire(eq(property), eq(true));
        var outcomes = race(1000, i -> attempt(owner, property, UUID.randomUUID(), true));
        assertThat(outcomes.stream().filter(result -> result.equals("SUCCESS")).count()).isEqualTo(1);
        assertThat(outcomes).allMatch(result -> Set.of("SUCCESS", "PROPERTY_UNAVAILABLE", "OPTIMISTIC_CONFLICT",
                "DATABASE_CONFLICT", "LOCK_BUSY").contains(result));
        assertSingleOwner(property);
    }

    @Test
    void expiredOwnerCannotDeleteTheSuccessorsRedisToken() {
        UUID property = UUID.randomUUID();
        var first = admission.acquire(property, true);
        redis.expire("lock:property:" + property, Duration.ZERO);
        var second = admission.acquire(property, true);
        String secondToken = redis.opsForValue().get("lock:property:" + property);
        first.close();
        assertThat(redis.opsForValue().get("lock:property:" + property)).isEqualTo(secondToken);
        second.close();
        assertThat(redis.hasKey("lock:property:" + property)).isFalse();
    }

    @Test
    void replayAfterCancellationKeepsOriginalResponseAndStaleCancellationCannotReleaseANewLease() {
        String owner = owner();
        UUID property = property(owner), reserveKey = UUID.randomUUID(), cancelKey = UUID.randomUUID();
        var original = reservations.reserve(owner, owner, property, reserveKey, false);
        UUID old = properties.findById(property).orElseThrow().getActiveLeaseId();
        reservations.cancel(owner, old, cancelKey);
        reservations.reserve(owner, owner, property, UUID.randomUUID(), false);
        UUID successor = properties.findById(property).orElseThrow().getActiveLeaseId();
        assertThat(reservations.reserve(owner, owner, property, reserveKey, false).body()).isEqualTo(original.body());
        assertThat(reservations.cancel(owner, old, cancelKey).replayed()).isTrue();
        assertThatThrownBy(() -> reservations.cancel(owner, old, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(409));
        assertThat(properties.findById(property).orElseThrow().getActiveLeaseId()).isEqualTo(successor);
        assertThat(leases.findById(successor).orElseThrow().getStatus()).isEqualTo(LeaseStatus.RESERVED);
    }

    @Test
    void sameKeyForAnotherPropertyIsRejectedWithoutClaimingIt() {
        String owner = owner();
        UUID first = property(owner), second = property(owner), key = UUID.randomUUID();
        reservations.reserve(owner, owner, first, key, false);
        assertThatThrownBy(() -> reservations.reserve(owner, owner, second, key, false))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(422));
        assertThat(properties.findById(second).orElseThrow().getStatus()).isEqualTo(PropertyStatus.AVAILABLE);
    }

    @Test
    void expirationRacingWithReplacementPreservesTheNewOwner() throws Exception {
        String owner = owner();
        UUID property = property(owner);
        reservations.reserve(owner, owner, property, UUID.randomUUID(), false);
        UUID old = properties.findById(property).orElseThrow().getActiveLeaseId();
        jdbc.update("""
                update lease set created_at = clock_timestamp() - interval '16 minutes',
                hold_expires_at = clock_timestamp() - interval '1 minute' where id = ?
                """, old);
        race(2, i -> {
            try {
                if (i == 0) expiration.expire(old);
                else reservations.reserve(owner, owner, property, UUID.randomUUID(), false);
                return "DONE";
            } catch (ApiException | org.springframework.dao.OptimisticLockingFailureException | DomainConflict expected) {
                return "CONFLICT";
            }
        });
        if (properties.findById(property).orElseThrow().getStatus() == PropertyStatus.AVAILABLE) {
            reservations.reserve(owner, owner, property, UUID.randomUUID(), false);
        }
        UUID successor = properties.findById(property).orElseThrow().getActiveLeaseId();
        assertThat(successor).isNotEqualTo(old);
        assertThat(leases.findById(old).orElseThrow().getStatus()).isEqualTo(LeaseStatus.EXPIRED);
        assertThat(expiration.expire(old)).isFalse();
        assertSingleOwner(property);
    }

    @Test
    void otherDemoSessionsCannotAccessOrReserveInventory() {
        String owner = owner();
        UUID property = property(owner);
        assertThatThrownBy(() -> reservations.reserve("another-session", property, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(404));
        assertThat(properties.findById(property).orElseThrow().getStatus()).isEqualTo(PropertyStatus.AVAILABLE);
    }

    private String attempt(String owner, UUID property, UUID key, boolean enabled) {
        try {
            reservations.reserve(owner, owner, property, key, enabled);
            return "SUCCESS";
        } catch (ApiException error) {
            return error.code();
        }
    }

    private void assertSingleOwner(UUID id) {
        var active = leases.findByPropertyIdOrderByCreatedAtDesc(id).stream()
                .filter(lease -> Set.of(LeaseStatus.RESERVED, LeaseStatus.CONFIRMING, LeaseStatus.COMPENSATING,
                        LeaseStatus.BOOKED).contains(lease.getStatus())).toList();
        assertThat(active).hasSize(1);
        var property = properties.findById(id).orElseThrow();
        assertThat(property.getStatus()).isEqualTo(PropertyStatus.RESERVED);
        assertThat(property.getActiveLeaseId()).isEqualTo(active.getFirst().getId());
    }

    private <T> List<T> race(int count, IntFunction<T> operation) throws Exception {
        var pool = Executors.newFixedThreadPool(Math.min(count, 32));
        var start = new CountDownLatch(1);
        try {
            List<Future<T>> tasks = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int contender = i;
                tasks.add(pool.submit(() -> { start.await(); return operation.apply(contender); }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> task : tasks) results.add(task.get(120, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }
}
