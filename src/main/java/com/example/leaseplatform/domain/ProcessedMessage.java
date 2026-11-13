package com.example.leaseplatform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "processed_message")
public class ProcessedMessage {
    @EmbeddedId
    private ProcessedMessageId id;
    @Column(nullable = false, updatable = false)
    private Instant processedAt;

    protected ProcessedMessage() {}

    public ProcessedMessage(UUID messageId, String consumerName, Instant now) {
        this.id = new ProcessedMessageId(messageId, consumerName);
        this.processedAt = Objects.requireNonNull(now);
    }

    public ProcessedMessageId getId() { return id; }
    public Instant getProcessedAt() { return processedAt; }
}
