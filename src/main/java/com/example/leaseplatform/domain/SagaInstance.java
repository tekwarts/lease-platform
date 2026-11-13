package com.example.leaseplatform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "saga_instance", uniqueConstraints = @UniqueConstraint(
        name = "uq_saga_lease_type", columnNames = {"lease_id", "saga_type"}))
public class SagaInstance extends VersionedEntity {
    @Column(nullable = false, updatable = false)
    private UUID leaseId;
    @Column(nullable = false, updatable = false, length = 64)
    private String sagaType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private SagaStatus status;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SagaStep currentStep;
    @Column(nullable = false)
    private Instant deadline;

    protected SagaInstance() {}

    public SagaInstance(UUID leaseId, Instant now, Instant deadline) {
        super(UUID.randomUUID(), now);
        this.leaseId = Objects.requireNonNull(leaseId);
        if (deadline == null || !deadline.isAfter(now)) {
            throw new IllegalArgumentException("Saga deadline must be in the future");
        }
        this.sagaType = "LEASE_CONFIRMATION";
        this.status = SagaStatus.RUNNING;
        this.currentStep = SagaStep.AUTHORIZE_PAYMENT;
        this.deadline = deadline;
    }

    public UUID getLeaseId() { return leaseId; }
    public String getSagaType() { return sagaType; }
    public SagaStatus getStatus() { return status; }
    public SagaStep getCurrentStep() { return currentStep; }
    public Instant getDeadline() { return deadline; }
}
