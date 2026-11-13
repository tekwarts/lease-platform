package com.example.leaseplatform.domain;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "property")
public class Property extends VersionedEntity {
    @Column(nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PropertyStatus status;

    private UUID activeLeaseId;

    @Column(length = 128, updatable = false)
    private String demoOwnerId;

    @Column(nullable = false, updatable = false)
    private boolean labProperty;

    protected Property() {}

    public Property(String displayName, Instant now) {
        super(UUID.randomUUID(), now);
        if (displayName == null || displayName.isBlank() || displayName.length() > 200) {
            throw new IllegalArgumentException("Display name must contain 1 to 200 characters");
        }
        this.displayName = displayName;
        this.status = PropertyStatus.AVAILABLE;
    }

    public static Property demo(String name, Instant now, String owner, boolean lab) {
        if (owner == null || owner.isBlank() || owner.length() > 128) {
            throw new IllegalArgumentException("A demo owner is required");
        }
        Property property = new Property(name, now);
        property.demoOwnerId = owner;
        property.labProperty = lab;
        return property;
    }

    /** Persist this property and the returned lease in the SAME local transaction. */
    public Lease reserve(String tenantId, Instant databaseNow, Duration holdDuration) {
        if (status != PropertyStatus.AVAILABLE || activeLeaseId != null) {
            throw new DomainConflict("Property is unavailable");
        }
        Objects.requireNonNull(holdDuration);
        if (holdDuration.isNegative() || holdDuration.isZero()) {
            throw new IllegalArgumentException("Hold duration must be positive");
        }
        Lease lease = new Lease(id, tenantId, databaseNow, databaseNow.plus(holdDuration));
        activeLeaseId = lease.getId();
        status = PropertyStatus.RESERVED;
        return lease;
    }

    public void beginConfirmation(Lease lease, Instant databaseNow) {
        requireOwnership(lease);
        lease.beginConfirmation(databaseNow);
    }

    /** Invoke only after the coordinator has durably verified payment capture. */
    public void completeBooking(Lease lease) {
        requireOwnership(lease);
        lease.book();
        status = PropertyStatus.BOOKED;
    }

    public void cancel(Lease lease) {
        requireOwnership(lease);
        lease.cancel();
        release();
    }

    public void expire(Lease lease, Instant databaseNow) {
        requireOwnership(lease);
        lease.expire(databaseNow);
        release();
    }

    public void beginCompensation(Lease lease) {
        requireOwnership(lease);
        lease.beginCompensation();
    }

    /** Invoke only after all remote compensation effects have been reconciled. */
    public void completeCompensation(Lease lease) {
        requireOwnership(lease);
        lease.finishCompensation();
        release();
    }

    private void requireOwnership(Lease lease) {
        Objects.requireNonNull(lease);
        if (status != PropertyStatus.RESERVED || !id.equals(lease.getPropertyId())
                || !lease.getId().equals(activeLeaseId)) {
            throw new DomainConflict("Lease does not own this property's reservation");
        }
    }

    private void release() {
        status = PropertyStatus.AVAILABLE;
        activeLeaseId = null;
    }

    public String getDisplayName() { return displayName; }
    public PropertyStatus getStatus() { return status; }
    public UUID getActiveLeaseId() { return activeLeaseId; }
    public String getDemoOwnerId() { return demoOwnerId; }
    public boolean isLabProperty() { return labProperty; }
}
