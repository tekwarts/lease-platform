package com.example.leaseplatform.service;

import com.example.leaseplatform.dto.Views.*;
import com.example.leaseplatform.exception.ApiException;
import com.example.leaseplatform.config.BookingSettings;
import com.example.leaseplatform.domain.*;
import com.example.leaseplatform.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Admission is outside the transaction; commit finishes BEFORE the Redis token
 * is released.
 */
@Service
public class ReservationService {
    private final PropertyRepository properties;
    private final LeaseRepository leases;
    private final IdempotencyRequestRepository idempotency;
    private final OutboxEventRepository outbox;
    private final TransactionTemplate transactions;
    private final DatabaseTime clock;
    private final ObjectMapper json;
    private final AdmissionLock admission;
    private final BookingSettings settings;

    public ReservationService(PropertyRepository properties, LeaseRepository leases,
            IdempotencyRequestRepository idempotency, OutboxEventRepository outbox,
            TransactionTemplate transactions, DatabaseTime clock, ObjectMapper json,
            AdmissionLock admission, BookingSettings settings) {
        this.properties = properties;
        this.leases = leases;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
        this.json = json;
        this.admission = admission;
        this.settings = settings;
    }

    public StoredResponse reserve(String owner, UUID propertyId, UUID key) {
        return reserve(owner, owner, propertyId, key, settings.redisEnabled());
    }

    // Used only by the bounded, server-controlled lab; HTTP clients cannot choose a
    // tenant ID.
    StoredResponse reserve(String inventoryOwner, String tenant, UUID propertyId, UUID key, boolean redisEnabled) {
        String hash = hash("RESERVE:" + propertyId);
        StoredResponse previous = replay(tenant, "RESERVE", key, hash);
        if (previous != null)
            return previous;
        try (AdmissionLock.Ticket ignored = admission.acquire(propertyId, redisEnabled)) {
            return mutate(tenant, "RESERVE", key, hash, () -> {
                Property property = properties.findByIdAndDemoOwnerId(propertyId, inventoryOwner)
                        .orElseThrow(ApiException::notFound);
                Instant now = clock.now();
                if (property.getStatus() == PropertyStatus.RESERVED) {
                    Lease active = leases.findById(property.getActiveLeaseId()).orElseThrow();
                    if (active.getStatus() == LeaseStatus.RESERVED && active.isHoldExpired(now)) {
                        property.expire(active, now);
                        emit(propertyId, active.getId(), "LeaseExpired", active, now);
                        // Remove the old row from the live index before the successor INSERT.
                        leases.flush();
                    }
                }
                Lease lease = property.reserve(tenant, now, settings.holdDuration());
                leases.saveAndFlush(lease);
                emit(propertyId, lease.getId(), "PropertyReserved", lease, now);
                return new StoredResponse(201, json.writeValueAsString(LeaseView.of(lease)), false);
            });
        } catch (ApiException error) {
            // An identical request may have committed while this caller waited for
            // admission.
            StoredResponse committed = replay(tenant, "RESERVE", key, hash);
            if (committed != null)
                return committed;
            throw error;
        }
    }

    public StoredResponse cancel(String tenant, UUID leaseId, UUID key) {
        return mutate(tenant, "CANCEL", key, hash("CANCEL:" + leaseId), () -> {
            Lease lease = leases.findByIdAndTenantId(leaseId, tenant).orElseThrow(ApiException::notFound);
            Property property = properties.findByIdAndDemoOwnerId(lease.getPropertyId(), tenant)
                    .orElseThrow(ApiException::notFound);
            Instant now = clock.now();
            if (lease.isHoldExpired(now) && lease.getStatus() == LeaseStatus.RESERVED) {
                property.expire(lease, now);
                emit(property.getId(), leaseId, "LeaseExpired", lease, now);
            } else {
                property.cancel(lease);
                emit(property.getId(), leaseId, "LeaseCancelled", lease, now);
            }
            return new StoredResponse(200, json.writeValueAsString(LeaseView.of(lease)), false);
        });
    }

    private StoredResponse mutate(String tenant, String operation, UUID key, String hash,
            Supplier<StoredResponse> change) {
        StoredResponse previous = replay(tenant, operation, key, hash);
        if (previous != null)
            return previous;
        try {
            return transactions.execute(tx -> {
                StoredResponse inside = replay(tenant, operation, key, hash);
                if (inside != null)
                    return inside;
                StoredResponse response = change.get();
                idempotency.saveAndFlush(new IdempotencyRequest(tenant, operation, key, hash,
                        response.status(), response.body(), clock.now()));
                return response;
            });
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException | DomainConflict error) {
            // The failed transaction has already rolled back; this read uses a fresh
            // transaction.
            StoredResponse committed = replay(tenant, operation, key, hash);
            if (committed != null)
                return committed;
            if (error instanceof ObjectOptimisticLockingFailureException) {
                throw new ApiException(409, "OPTIMISTIC_CONFLICT",
                        "Inventory changed concurrently. Refresh and retry.");
            }
            if (error instanceof DataIntegrityViolationException) {
                if (!constraint(error, "uq_lease_one_active_per_property")
                        && !constraint(error, "uq_idempotency_identity")) {
                    throw error; // An unrelated schema/programming error is not a business rejection.
                }
                throw new ApiException(409, "DATABASE_CONFLICT",
                        "Another reservation committed first. Refresh availability.");
            }
            throw new ApiException(409, "PROPERTY_UNAVAILABLE", error.getMessage());
        }
    }

    private StoredResponse replay(String user, String operation, UUID key, String hash) {
        return idempotency.findByUserIdAndOperationAndIdempotencyKey(user, operation, key).map(record -> {
            if (!record.matchesRequest(hash)) {
                throw new ApiException(422, "IDEMPOTENCY_MISMATCH",
                        "This key was already used for a different request.");
            }
            return new StoredResponse(record.getResponseStatus(), record.getResponseBody(), true);
        }).orElse(null);
    }

    void emit(UUID propertyId, UUID leaseId, String type, Lease lease, Instant now) {
        outbox.save(
                new OutboxEvent(propertyId, leaseId, null, type, json.writeValueAsString(LeaseView.of(lease)), now));
    }

    private static boolean constraint(Throwable error, String name) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(name))
                return true;
        }
        return false;
    }

    private static String hash(String identity) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", error);
        }
    }
}
