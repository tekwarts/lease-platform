package com.example.leaseplatform.service;

import com.example.leaseplatform.dto.Views.*;
import com.example.leaseplatform.exception.ApiException;
import com.example.leaseplatform.domain.*;
import com.example.leaseplatform.repository.*;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.PreDestroy;

@Service
public class ConcurrencyLab {
    private static final Logger log = LoggerFactory.getLogger(ConcurrencyLab.class);
    private static final Set<LeaseStatus> LIVE = EnumSet.of(LeaseStatus.RESERVED, LeaseStatus.CONFIRMING,
            LeaseStatus.COMPENSATING, LeaseStatus.BOOKED);
    private final Semaphore capacity = new Semaphore(1);
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(32, 32, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1000), Thread.ofPlatform().daemon().name("leaseflow-lab-", 0).factory());
    private final DemoInventory inventory;
    private final ReservationService reservations;
    private final PropertyRepository properties;
    private final LeaseRepository leases;
    private final TransactionTemplate transactions;

    public ConcurrencyLab(DemoInventory inventory, ReservationService reservations, PropertyRepository properties,
                          LeaseRepository leases, TransactionTemplate transactions) {
        this.inventory = inventory;
        this.reservations = reservations;
        this.properties = properties;
        this.leases = leases;
        this.transactions = transactions;
    }

    public Result run(String owner, int attempts, boolean redisEnabled) {
        if (attempts != 10 && attempts != 100 && attempts != 1000) {
            throw new ApiException(400, "INVALID_RUN_SIZE", "Choose 10, 100, or 1000 attempts.");
        }
        if (!capacity.tryAcquire()) throw new ApiException(429, "LAB_BUSY", "Another test is running. Try again shortly.");
        int parallelism = Math.min(32, attempts);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(parallelism);
        List<Future<?>> futures = new ArrayList<>();
        var counts = new ConcurrentHashMap<String, AtomicInteger>();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        long began = System.nanoTime();
        try {
            UUID propertyId = inventory.trialProperty(owner);
            for (int i = 0; i < attempts; i++) {
                String contender = owner + ":request-" + i;
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Start barrier timed out");
                        reservations.reserve(owner, contender, propertyId, UUID.randomUUID(), redisEnabled);
                        increment(counts, "successes");
                    } catch (ApiException error) {
                        String category = switch (error.code()) {
                            case "PROPERTY_UNAVAILABLE" -> "unavailable";
                            case "LOCK_BUSY" -> "lockContention";
                            case "OPTIMISTIC_CONFLICT" -> "optimisticConflicts";
                            case "DATABASE_CONFLICT" -> "databaseConflicts";
                            default -> "unexpectedErrors";
                        };
                        increment(counts, category);
                        if (category.equals("unexpectedErrors")) sample(errors, error.code());
                    } catch (Exception error) {
                        if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                        increment(counts, "unexpectedErrors");
                        sample(errors, error.getClass().getSimpleName());
                        log.error("Concurrency test attempt failed for property {}", propertyId, error);
                    }
                }));
            }
            if (!ready.await(15, TimeUnit.SECONDS)) throw new TimeoutException("Workers did not reach the start barrier");
            began = System.nanoTime();
            start.countDown();
            long deadline = began + TimeUnit.SECONDS.toNanos(120);
            for (Future<?> future : futures) {
                future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
            Metrics metrics = new Metrics(value(counts, "successes"), value(counts, "unavailable"),
                    value(counts, "lockContention"), value(counts, "optimisticConflicts"),
                    value(counts, "databaseConflicts"), value(counts, "unexpectedErrors"));
            return transactions.execute(tx -> {
                Property property = properties.findByIdAndDemoOwnerId(propertyId, owner).orElseThrow();
                var active = leases.findByPropertyIdOrderByCreatedAtDesc(propertyId).stream()
                        .filter(lease -> LIVE.contains(lease.getStatus())).toList();
                Lease winner = active.size() == 1 ? active.getFirst() : null;
                boolean verified = metrics.successes() == 1 && metrics.unexpectedErrors() == 0
                        && winner != null && property.getStatus() == PropertyStatus.RESERVED
                        && winner.getId().equals(property.getActiveLeaseId());
                return new Result(UUID.randomUUID(), attempts, parallelism, redisEnabled, elapsedMs, metrics,
                        PropertyView.of(property), active.size(), winner == null ? null : LeaseView.of(winner),
                        verified, List.copyOf(errors));
            });
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "RUN_INTERRUPTED", "Run interrupted. Its isolated inventory is retained.");
        } catch (ExecutionException | TimeoutException | RejectedExecutionException error) {
            throw new ApiException(503, "RUN_INCOMPLETE", "Run did not finish. No verification result is claimed.");
        } finally {
            start.countDown();
            futures.forEach(future -> { if (!future.isDone()) future.cancel(true); });
            workers.purge();
            capacity.release();
        }
    }

    @PreDestroy
    void shutdown() { workers.shutdownNow(); }

    private static void increment(Map<String, AtomicInteger> counts, String key) {
        counts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
    }

    private static int value(Map<String, AtomicInteger> counts, String key) {
        AtomicInteger count = counts.get(key);
        return count == null ? 0 : count.get();
    }

    private static void sample(List<String> errors, String detail) {
        synchronized (errors) { if (errors.size() < 5) errors.add(detail); }
    }

    public record Metrics(int successes, int unavailable, int lockContention, int optimisticConflicts,
                          int databaseConflicts, int unexpectedErrors) {}
    public record Result(UUID id, int attempts, int parallelism, boolean redisEnabled, long elapsedMs,
                         Metrics metrics, PropertyView property, int activeLeaseCount, LeaseView activeLease,
                         boolean verified, List<String> errors) {}
}
