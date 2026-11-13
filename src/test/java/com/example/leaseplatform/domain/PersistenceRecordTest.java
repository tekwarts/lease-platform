package com.example.leaseplatform.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class PersistenceRecordTest {
    private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");

    @Test
    void idempotencyKeepsTheOriginalSuccessfulResponseUnchanged() {
        String body = "{\"leaseId\":\"original\"}";
        IdempotencyRequest request = new IdempotencyRequest("tenant", "RESERVE", UUID.randomUUID(),
                "a".repeat(64), 201, body, NOW);
        assertThat(request.getResponseStatus()).isEqualTo(201);
        assertThat(request.getResponseBody()).isEqualTo(body);
        assertThat(request.matchesRequest("a".repeat(64))).isTrue();
        assertThat(request.matchesRequest("b".repeat(64))).isFalse();
        assertThatThrownBy(() -> new IdempotencyRequest("tenant", "RESERVE", UUID.randomUUID(),
                "a".repeat(64), 409, body, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inboxIdentityIncludesTheLogicalConsumer() {
        UUID message = UUID.randomUUID();
        ProcessedMessageId first = new ProcessedMessageId(message, "booking");
        assertThat(first).isEqualTo(new ProcessedMessageId(message, "booking"));
        assertThat(first.hashCode()).isEqualTo(new ProcessedMessageId(message, "booking").hashCode());
        assertThat(first).isNotEqualTo(new ProcessedMessageId(message, "payment"));
    }

    @Test
    void newSagaHasItsOwnDeadlineAndStartsAtAuthorization() {
        SagaInstance saga = new SagaInstance(UUID.randomUUID(), NOW, NOW.plusSeconds(120));
        assertThat(saga.getStatus()).isEqualTo(SagaStatus.RUNNING);
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.AUTHORIZE_PAYMENT);
        assertThat(saga.getDeadline()).isEqualTo(NOW.plusSeconds(120));
        assertThatThrownBy(() -> new SagaInstance(UUID.randomUUID(), NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
