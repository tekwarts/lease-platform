package com.example.leaseplatform.domain;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class PropertyTest {
    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");
    private static final Duration HOLD = Duration.ofMinutes(15);

    @Test
    void reserveAssignsAnOwnerAndPreventsASecondLease() {
        Property property = new Property("Harbour Loft", NOW);
        Lease lease = property.reserve("tenant-a", NOW, HOLD);
        assertThat(lease.getId()).isNotNull();
        assertThat(lease.getPropertyId()).isEqualTo(property.getId());
        assertThat(lease.getHoldExpiresAt()).isEqualTo(NOW.plus(HOLD));
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.RESERVED);
        assertThat(property.getActiveLeaseId()).isEqualTo(lease.getId());
        assertThat(property.getStatus()).isEqualTo(PropertyStatus.RESERVED);
        assertThatThrownBy(() -> property.reserve("tenant-b", NOW, HOLD))
                .isInstanceOf(DomainConflict.class);
        assertThat(property.getActiveLeaseId()).isEqualTo(lease.getId());
    }

    @Test
    void expiredHoldCannotConfirmAtTheExactDeadline() {
        Property property = new Property("Loft", NOW);
        Lease lease = property.reserve("tenant-a", NOW, HOLD);
        assertThatThrownBy(() -> property.beginConfirmation(lease, NOW.plus(HOLD)))
                .isInstanceOf(DomainConflict.class);
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.RESERVED);
        assertThat(property.getStatus()).isEqualTo(PropertyStatus.RESERVED);
    }

    @Test
    void expirationReleasesBothSidesAtTheDeadline() {
        Property property = new Property("Loft", NOW);
        Lease lease = property.reserve("tenant-a", NOW, HOLD);
        assertThatThrownBy(() -> property.expire(lease, NOW.plusSeconds(1)))
                .isInstanceOf(DomainConflict.class);
        assertThat(property.getActiveLeaseId()).isEqualTo(lease.getId());
        property.expire(lease, NOW.plus(HOLD));
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.EXPIRED);
        assertThat(property.getStatus()).isEqualTo(PropertyStatus.AVAILABLE);
        assertThat(property.getActiveLeaseId()).isNull();
    }

    @Test
    void confirmingLeaseIgnoresTheOriginalHoldTimer() {
        Property property = new Property("Loft", NOW);
        Lease lease = property.reserve("tenant-a", NOW, HOLD);
        property.beginConfirmation(lease, NOW.plusSeconds(1));
        assertThatThrownBy(() -> property.expire(lease, NOW.plus(HOLD)))
                .isInstanceOf(DomainConflict.class);
        assertThatThrownBy(() -> property.cancel(lease)).isInstanceOf(DomainConflict.class);
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.CONFIRMING);
        assertThat(property.getActiveLeaseId()).isEqualTo(lease.getId());
    }

    @Test
    void delayedCancellationCannotReleaseTheNewOwner() {
        Property property = new Property("Loft", NOW);
        Lease oldLease = property.reserve("tenant-a", NOW, HOLD);
        property.cancel(oldLease);
        Lease newLease = property.reserve("tenant-b", NOW.plusSeconds(1), HOLD);
        assertThatThrownBy(() -> property.cancel(oldLease)).isInstanceOf(DomainConflict.class);
        assertThatThrownBy(() -> property.expire(oldLease, NOW.plus(HOLD)))
                .isInstanceOf(DomainConflict.class);
        assertThat(property.getActiveLeaseId()).isEqualTo(newLease.getId());
        assertThat(newLease.getStatus()).isEqualTo(LeaseStatus.RESERVED);
    }

    @Test
    void leaseOfAnotherPropertyCannotMutateInventory() {
        Property a = new Property("A", NOW);
        Property b = new Property("B", NOW);
        Lease leaseA = a.reserve("tenant-a", NOW, HOLD);
        Lease leaseB = b.reserve("tenant-b", NOW, HOLD);
        assertThatThrownBy(() -> a.cancel(leaseB)).isInstanceOf(DomainConflict.class);
        assertThatThrownBy(() -> a.beginConfirmation(leaseB, NOW)).isInstanceOf(DomainConflict.class);
        assertThat(a.getActiveLeaseId()).isEqualTo(leaseA.getId());
    }

    @Test
    void completeBookingRequiresConfirmationAndRetainsOwnership() {
        Property property = new Property("Loft", NOW);
        Lease lease = property.reserve("tenant-a", NOW, HOLD);
        assertThatThrownBy(() -> property.completeBooking(lease)).isInstanceOf(DomainConflict.class);
        property.beginConfirmation(lease, NOW.plusSeconds(1));
        property.completeBooking(lease);
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.BOOKED);
        assertThat(property.getStatus()).isEqualTo(PropertyStatus.BOOKED);
        assertThat(property.getActiveLeaseId()).isEqualTo(lease.getId());
        assertThatThrownBy(() -> property.cancel(lease)).isInstanceOf(DomainConflict.class);
        assertThatThrownBy(() -> property.beginCompensation(lease)).isInstanceOf(DomainConflict.class);
    }

    @Test
    void compensationRetainsInventoryUntilEffectsAreReconciled() {
        Property property = new Property("Loft", NOW);
        Lease lease = property.reserve("tenant-a", NOW, HOLD);
        property.beginConfirmation(lease, NOW.plusSeconds(1));
        assertThatThrownBy(() -> property.completeCompensation(lease)).isInstanceOf(DomainConflict.class);
        property.beginCompensation(lease);
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.COMPENSATING);
        assertThat(property.getActiveLeaseId()).isEqualTo(lease.getId());
        assertThatThrownBy(() -> property.expire(lease, NOW.plus(HOLD))).isInstanceOf(DomainConflict.class);
        assertThatThrownBy(() -> property.completeBooking(lease)).isInstanceOf(DomainConflict.class);
        property.completeCompensation(lease);
        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.FAILED);
        assertThat(property.getStatus()).isEqualTo(PropertyStatus.AVAILABLE);
        assertThat(property.getActiveLeaseId()).isNull();
    }

    @Test
    void invalidReservationLeavesPropertyAvailable() {
        Property property = new Property("Loft", NOW);
        assertThatThrownBy(() -> property.reserve(" ", NOW, HOLD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> property.reserve("tenant", NOW, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> property.reserve("tenant", NOW, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(property.getStatus()).isEqualTo(PropertyStatus.AVAILABLE);
        assertThat(property.getActiveLeaseId()).isNull();
    }
}
