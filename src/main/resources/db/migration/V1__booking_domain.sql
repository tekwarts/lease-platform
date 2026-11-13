CREATE TABLE property (
    id UUID PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL CHECK (length(trim(display_name)) > 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('AVAILABLE', 'RESERVED', 'BOOKED')),
    active_lease_id UUID,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_property_ownership CHECK (
        (status = 'AVAILABLE' AND active_lease_id IS NULL) OR
        (status IN ('RESERVED', 'BOOKED') AND active_lease_id IS NOT NULL)
    )
);

CREATE TABLE lease (
    id UUID PRIMARY KEY,
    property_id UUID NOT NULL REFERENCES property(id),
    tenant_id VARCHAR(128) NOT NULL CHECK (length(trim(tenant_id)) > 0),
    status VARCHAR(24) NOT NULL CHECK (status IN (
        'RESERVED', 'CONFIRMING', 'COMPENSATING', 'BOOKED', 'CANCELLED', 'EXPIRED', 'FAILED'
    )),
    hold_expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_lease_hold_deadline CHECK (hold_expires_at > created_at),
    CONSTRAINT uq_lease_property_id UNIQUE (property_id, id)
);

-- Authoritative last line of defence, independent of Redis and application locking.
CREATE UNIQUE INDEX uq_lease_one_active_per_property ON lease(property_id)
    WHERE status IN ('RESERVED', 'CONFIRMING', 'COMPENSATING', 'BOOKED');

-- Deferral allows both sides of the ownership cycle to be written in one transaction.
-- The composite FK also prevents pointing a property at another property's lease.
ALTER TABLE property ADD CONSTRAINT fk_property_active_lease
    FOREIGN KEY (id, active_lease_id) REFERENCES lease(property_id, id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE INDEX ix_lease_expiration ON lease(hold_expires_at, id) WHERE status = 'RESERVED';
CREATE INDEX ix_lease_property_history ON lease(property_id, created_at DESC);
CREATE INDEX ix_lease_tenant ON lease(tenant_id, created_at DESC);

CREATE TABLE idempotency_request (
    id UUID PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL CHECK (length(trim(user_id)) > 0),
    operation VARCHAR(64) NOT NULL CHECK (length(trim(operation)) > 0),
    idempotency_key UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    response_status INTEGER NOT NULL CHECK (response_status BETWEEN 200 AND 299),
    response_body TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_identity UNIQUE (user_id, operation, idempotency_key)
);

CREATE TABLE saga_instance (
    id UUID PRIMARY KEY,
    lease_id UUID NOT NULL REFERENCES lease(id),
    saga_type VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN (
        'RUNNING', 'COMPENSATING', 'COMPLETED', 'COMPENSATED', 'MANUAL_REVIEW'
    )),
    current_step VARCHAR(32) NOT NULL CHECK (current_step IN (
        'AUTHORIZE_PAYMENT', 'PREPARE_CONTRACT', 'CAPTURE_PAYMENT', 'RECONCILE', 'DONE'
    )),
    deadline TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_saga_deadline CHECK (deadline > created_at),
    CONSTRAINT uq_saga_lease_type UNIQUE (lease_id, saga_type)
);

CREATE INDEX ix_saga_recovery ON saga_instance(status, deadline)
    WHERE status IN ('RUNNING', 'COMPENSATING');

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID,
    event_type VARCHAR(128) NOT NULL CHECK (length(trim(event_type)) > 0),
    payload JSONB NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING', 'PUBLISHED')),
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_outbox_publication CHECK (
        (status = 'PENDING' AND published_at IS NULL) OR
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
    )
);

CREATE INDEX ix_outbox_pending ON outbox_event(next_attempt_at, created_at, id)
    WHERE status = 'PENDING';
CREATE INDEX ix_outbox_correlation ON outbox_event(correlation_id, created_at);

CREATE TABLE processed_message (
    message_id UUID NOT NULL,
    consumer_name VARCHAR(128) NOT NULL CHECK (length(trim(consumer_name)) > 0),
    processed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (message_id, consumer_name)
);
