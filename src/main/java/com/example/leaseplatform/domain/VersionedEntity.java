package com.example.leaseplatform.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@MappedSuperclass
public abstract class VersionedEntity {
    @Id
    @Column(nullable = false, updatable = false)
    protected UUID id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected VersionedEntity() {}

    protected VersionedEntity(UUID id, Instant now) {
        this.id = java.util.Objects.requireNonNull(id);
        this.createdAt = java.util.Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    @PreUpdate
    protected void updated() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
