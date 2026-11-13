package com.example.leaseplatform.dto;

import com.example.leaseplatform.domain.*;
import java.time.Instant;
import java.util.UUID;

public final class Views {
    private Views() {}

    public record PropertyView(UUID id, String displayName, PropertyStatus status, UUID activeLeaseId, Long version) {
        public static PropertyView of(Property property) {
            return new PropertyView(property.getId(), property.getDisplayName(), property.getStatus(),
                    property.getActiveLeaseId(), property.getVersion());
        }
    }

    public record LeaseView(UUID id, UUID propertyId, LeaseStatus status, Instant holdExpiresAt, Instant createdAt) {
        public static LeaseView of(Lease lease) {
            return new LeaseView(lease.getId(), lease.getPropertyId(), lease.getStatus(),
                    lease.getHoldExpiresAt(), lease.getCreatedAt());
        }
    }

    public record StoredResponse(int status, String body, boolean replayed) {}
}
