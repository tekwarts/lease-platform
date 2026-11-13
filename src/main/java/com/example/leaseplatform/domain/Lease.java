package com.example.leaseplatform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "lease")
public class Lease extends VersionedEntity {
    @Column(nullable = false, updatable = false)
    private UUID propertyId;

    @Column(nullable = false, updatable = false, length = 128)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private LeaseStatus status;

    @Column(nullable = false, updatable = false)
    private Instant holdExpiresAt;

    protected Lease() {}

    // Property is the entry point for mutations so both sides transition together.
    Lease(UUID propertyId, String tenantId, Instant now, Instant holdExpiresAt) {
        super(UUID.randomUUID(), now);
        this.propertyId = Objects.requireNonNull(propertyId);
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 128) {
            throw new IllegalArgumentException("Tenant ID must contain 1 to 128 characters");
        }
        if (holdExpiresAt == null || !holdExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException("Hold deadline must be in the future");
        }
        this.tenantId = tenantId;
        this.holdExpiresAt = holdExpiresAt;
        this.status = LeaseStatus.RESERVED;
    }

    void beginConfirmation(Instant now) {
        require(LeaseStatus.RESERVED);
        if (isHoldExpired(now)) throw new DomainConflict("Reservation hold expired");
        status = LeaseStatus.CONFIRMING;
    }

    void book() {
        require(LeaseStatus.CONFIRMING);
        status = LeaseStatus.BOOKED;
    }

    void cancel() {
        require(LeaseStatus.RESERVED);
        status = LeaseStatus.CANCELLED;
    }

    void expire(Instant now) {
        require(LeaseStatus.RESERVED);
        if (!isHoldExpired(now)) throw new DomainConflict("Reservation hold is still valid");
        status = LeaseStatus.EXPIRED;
    }

    void beginCompensation() {
        require(LeaseStatus.CONFIRMING);
        status = LeaseStatus.COMPENSATING;
    }

    void finishCompensation() {
        require(LeaseStatus.COMPENSATING);
        status = LeaseStatus.FAILED;
    }

    private void require(LeaseStatus expected) {
        if (status != expected) throw new DomainConflict("Lease must be " + expected + ", was " + status);
    }

    public boolean isHoldExpired(Instant now) { return !holdExpiresAt.isAfter(Objects.requireNonNull(now)); }
    public UUID getPropertyId() { return propertyId; }
    public String getTenantId() { return tenantId; }
    public LeaseStatus getStatus() { return status; }
    public Instant getHoldExpiresAt() { return holdExpiresAt; }
}
