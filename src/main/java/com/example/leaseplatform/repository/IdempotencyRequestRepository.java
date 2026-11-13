package com.example.leaseplatform.repository;

import com.example.leaseplatform.domain.IdempotencyRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRequestRepository extends JpaRepository<IdempotencyRequest, UUID> {
    Optional<IdempotencyRequest> findByUserIdAndOperationAndIdempotencyKey(
            String userId, String operation, UUID idempotencyKey);
}
