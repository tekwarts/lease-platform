package com.example.leaseplatform.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
public class OutboxEvent {
    // The outbox primary key is also the stable message ID used for redelivery.
    @Id
    private UUID id;
    @Column(nullable = false, updatable = false)
    private UUID aggregateId;
    @Column(nullable = false, updatable = false)
    private UUID correlationId;
    @Column(updatable = false)
    private UUID causationId;
    @Column(nullable = false, updatable = false, length = 128)
    private String eventType;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private OutboxStatus status;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    private Instant publishedAt;
    @Column(nullable = false)
    private int retryCount;
    @Column(nullable = false)
    private Instant nextAttemptAt;

    protected OutboxEvent() {}

    public OutboxEvent(UUID aggregateId, UUID correlationId, UUID causationId,
                       String eventType, String payload, Instant now) {
        if (eventType == null || eventType.isBlank() || eventType.length() > 128) {
            throw new IllegalArgumentException("Event type must contain 1 to 128 characters");
        }
        this.id = UUID.randomUUID();
        this.aggregateId = Objects.requireNonNull(aggregateId);
        this.correlationId = Objects.requireNonNull(correlationId);
        this.causationId = causationId;
        this.eventType = eventType;
        this.payload = Objects.requireNonNull(payload);
        this.status = OutboxStatus.PENDING;
        this.createdAt = Objects.requireNonNull(now);
        this.nextAttemptAt = now;
    }

    public UUID getId() { return id; }
    public UUID getAggregateId() { return aggregateId; }
    public UUID getCorrelationId() { return correlationId; }
    public UUID getCausationId() { return causationId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getRetryCount() { return retryCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
}
