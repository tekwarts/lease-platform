package com.example.leaseplatform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "idempotency_request", uniqueConstraints = @UniqueConstraint(
        name = "uq_idempotency_identity", columnNames = {"user_id", "operation", "idempotency_key"}))
public class IdempotencyRequest {
    @Id
    private UUID id;
    @Column(nullable = false, updatable = false, length = 128)
    private String userId;
    @Column(nullable = false, updatable = false, length = 64)
    private String operation;
    @Column(nullable = false, updatable = false)
    private UUID idempotencyKey;
    @Column(nullable = false, updatable = false, length = 64)
    private String requestHash;
    @Column(nullable = false, updatable = false)
    private int responseStatus;
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String responseBody;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected IdempotencyRequest() {}

    public IdempotencyRequest(String userId, String operation, UUID key, String requestHash,
                              int responseStatus, String responseBody, Instant now) {
        if (userId == null || userId.isBlank() || userId.length() > 128
                || operation == null || operation.isBlank() || operation.length() > 64) {
            throw new IllegalArgumentException("A user and operation are required");
        }
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Request hash must be a lowercase SHA-256 hex digest");
        }
        if (responseStatus < 200 || responseStatus > 299) {
            throw new IllegalArgumentException("Only successful responses may be committed");
        }
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.operation = operation;
        this.idempotencyKey = Objects.requireNonNull(key);
        this.requestHash = requestHash;
        this.responseStatus = responseStatus;
        this.responseBody = Objects.requireNonNull(responseBody);
        this.createdAt = Objects.requireNonNull(now);
    }

    public boolean matchesRequest(String hash) { return requestHash.equals(hash); }
    public UUID getId() { return id; }
    public String getUserId() { return userId; }
    public String getOperation() { return operation; }
    public UUID getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public int getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }
}
