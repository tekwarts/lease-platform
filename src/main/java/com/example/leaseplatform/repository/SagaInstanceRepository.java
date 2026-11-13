package com.example.leaseplatform.repository;

import com.example.leaseplatform.domain.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SagaInstanceRepository extends JpaRepository<SagaInstance, UUID> {
    Optional<SagaInstance> findByLeaseIdAndSagaType(UUID leaseId, String sagaType);
    List<SagaInstance> findByStatusAndDeadlineLessThanEqualOrderByDeadlineAsc(
            SagaStatus status, Instant deadline, Pageable pageable);
}
