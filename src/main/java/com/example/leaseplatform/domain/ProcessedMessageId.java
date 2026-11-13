package com.example.leaseplatform.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class ProcessedMessageId implements Serializable {
    @Column(nullable = false, updatable = false)
    private UUID messageId;
    @Column(nullable = false, updatable = false, length = 128)
    private String consumerName;

    protected ProcessedMessageId() {}

    public ProcessedMessageId(UUID messageId, String consumerName) {
        this.messageId = Objects.requireNonNull(messageId);
        if (consumerName == null || consumerName.isBlank() || consumerName.length() > 128) {
            throw new IllegalArgumentException("A logical consumer name is required");
        }
        this.consumerName = consumerName;
    }

    public UUID getMessageId() { return messageId; }
    public String getConsumerName() { return consumerName; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ProcessedMessageId that)) return false;
        return Objects.equals(messageId, that.messageId) && Objects.equals(consumerName, that.consumerName);
    }

    @Override
    public int hashCode() { return Objects.hash(messageId, consumerName); }
}
