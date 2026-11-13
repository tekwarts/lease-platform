package com.example.leaseplatform.repository;

import com.example.leaseplatform.domain.Lease;
import com.example.leaseplatform.domain.LeaseStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LeaseRepository extends JpaRepository<Lease, UUID> {
    Optional<Lease> findByIdAndTenantId(UUID id, String tenantId);
    List<Lease> findByPropertyIdOrderByCreatedAtDesc(UUID propertyId);
    List<Lease> findByStatusAndHoldExpiresAtLessThanEqualOrderByHoldExpiresAtAsc(
            LeaseStatus status, Instant deadline, Pageable pageable);
}
