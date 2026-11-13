# Project Specification: LeaseFlow

## 1. Project Overview

Build **LeaseFlow**, a production-oriented, distributed property reservation and leasing platform designed to demonstrate Senior Software Engineer-level backend engineering.

The project must include a fully functional backend, a clean interactive React website, and deployment instructions.

The website must allow interviewers to interact with the real backend and observe concurrency control, distributed transactions, Saga execution, Kafka events, and failure recovery.

**Priorities:**

1. Correctness under high concurrency.
2. Industry-standard Java and Spring engineering practices.
3. Reliable distributed transaction processing.
4. Clean architecture with clear service boundaries.
5. Simplicity: avoid unnecessary frameworks, abstractions, and infrastructure.
6. A polished, interactive website suitable for a technical portfolio.

The system uses a **status-based, two-phase leasing workflow: Reserve → Confirm**.

Each property can have at most one active lease. There is no date-range booking or overlapping-interval logic.

---

## 2. Technology Stack

### Backend

- Java 21
- Spring Boot (current stable supported release)
- Spring Web MVC
- Spring Data JPA / Hibernate
- Spring Security
- PostgreSQL
- Redis
- Apache Kafka
- Flyway for database migrations
- Maven
- JUnit 5, Mockito, Testcontainers

Use standard Spring Boot conventions and dependency injection.

Prefer JPA repositories and entity methods over handwritten SQL. Use PostgreSQL-specific SQL only where appropriate, particularly for partial unique indexes and carefully justified atomic conditional updates.

Use `@Version` optimistic locking for entities that participate in concurrent state transitions.

### Frontend

- React + TypeScript
- Vite
- Tailwind CSS
- TanStack Query
- React Router

Build a professional engineering dashboard, not a full real-estate marketplace.

### Infrastructure

- Docker / Docker Compose
- GitHub Actions
- Separate databases for independently deployed business services
- Environment-based configuration
- Spring Boot Actuator and Micrometer for basic observability

Use current, compatible stable dependency versions. Verify APIs against their official documentation rather than assuming version compatibility.

Do not introduce Kubernetes, Elasticsearch, MongoDB, or an LLM in the initial implementation.

---

## 3. Service Architecture

Implement three independently deployable Spring Boot services in one Maven multi-module repository.

### A. Booking Service

This is the main application.

It owns:

- Property inventory
- Lease lifecycle
- Redis admission locking
- Optimistic concurrency control
- HTTP request idempotency
- Saga orchestration
- Transactional outbox
- Kafka consumer deduplication
- Reservation expiration and recovery

**Property and Lease must share the same PostgreSQL database and local transaction boundary.**

Do not separate them into independent microservices. Their atomic updates are critical to the design.

### B. Payment Service

An independent mock payment service with its own PostgreSQL database.

Support:

- AuthorizePayment
- CapturePayment
- VoidAuthorization
- RefundPayment when necessary
- Simulated payment failures
- Idempotent command processing
- Transactional outbox and inbox

Do not integrate real payment credentials or charge real money.

### C. Contract Service

An independent mock contract-processing service with its own PostgreSQL database.

Support:

- PrepareContract
- ContractPrepared
- ContractPreparationFailed
- VoidContractDraft

Contract preparation is reversible. Do not simulate an irreversible legal signature and then pretend it can simply be rolled back.

Both Payment and Contract services communicate with Booking Service through Kafka.

---

## 4. Core Domain Entities

### Property

Fields:

- id
- displayName
- status: AVAILABLE, RESERVED, BOOKED
- activeLeaseId (nullable)
- version (`@Version`)
- createdAt
- updatedAt

Business rules:

- AVAILABLE: no active lease
- RESERVED: exactly one current lease owns the hold
- BOOKED: the lease has been finalized
- Only the active lease may confirm or release the property

### Lease

Fields:

- id (backend-generated)
- propertyId
- tenantId
- status
- holdExpiresAt
- version (`@Version`)
- createdAt
- updatedAt

Statuses:

- RESERVED
- CONFIRMING
- COMPENSATING
- BOOKED
- CANCELLED
- EXPIRED
- FAILED

Property and Lease must transition consistently inside one database transaction.

A property may have multiple historical leases but only one active lease.

Create a PostgreSQL partial unique index on `lease(property_id)` for statuses:

- RESERVED
- CONFIRMING
- COMPENSATING
- BOOKED

This index must prevent two active/live leases for the same property, even if application code contains a concurrency bug.

### IdempotencyRequest

Fields:

- id
- userId
- operation
- idempotencyKey
- requestHash
- responseStatus
- responseBody
- createdAt

Unique constraint:

`(userId, operation, idempotencyKey)`

The backend must persist the original successful HTTP response in PostgreSQL in the same transaction as the corresponding business changes.

### SagaInstance

Fields:

- id
- leaseId
- sagaType
- status
- currentStep
- deadline
- version
- createdAt
- updatedAt

### OutboxEvent

Fields:

- id
- aggregateId
- eventType
- payload
- status
- createdAt
- publishedAt
- retryCount

### ProcessedMessage

Fields:

- messageId
- consumerName
- processedAt

Use a uniqueness constraint to prevent duplicate processing by the same logical consumer.

The Payment and Contract services should also have their own domain records, outbox, and processed-message tables.

---

## 5. Core Business Workflow

### Phase 1 — Reserve Property (Synchronous)

Endpoint:

`POST /api/v1/properties/{propertyId}/leases`

Request header:

`Idempotency-Key: "<client-generated-UUID>"`

The frontend generates an idempotency key for each new Reserve action and reuses it for retries.

The backend executes the following:

1. Authenticate the user.
2. Validate the request and inspect the durable idempotency record.
3. If the same operation has already committed, return the original stored HTTP response.
4. Otherwise, attempt to acquire a Redis property lock using `SET NX` with a bounded TTL and unique ownership token.
5. If the lock is busy, return a documented transient response with retry guidance.
6. Start one PostgreSQL transaction.
7. Recheck durable idempotency.
8. Load the Property and verify availability.
9. If the existing hold has expired, safely expire the previous Lease and release its ownership as part of the same transaction.
10. Generate a new Lease ID on the backend.
11. Create the Lease with status RESERVED and a 15-minute expiration.
12. Set Property to RESERVED and assign its activeLeaseId.
13. Enforce optimistic locking through JPA `@Version`.
14. Persist the idempotency response and an outbox event in the same transaction.
15. Commit.
16. Release Redis using an atomic ownership-token check.
17. Return `201 Created` with leaseId and holdExpiresAt.

Redis is an optimization, not the authoritative correctness mechanism.

The PostgreSQL transaction, optimistic locking, and partial unique index must prevent double booking even when Redis expires early or multiple application instances enter the critical section.

Do not create Kafka commands or Saga instances for rejected Reserve attempts.

### Phase 2 — Confirm Lease (Asynchronous)

Endpoint:

`POST /api/v1/leases/{leaseId}/confirm`

Use a new idempotency key for Confirm.

In one local transaction:

1. Verify the authenticated user's ownership.
2. Verify that the Lease is RESERVED and its hold is still valid.
3. Change Lease to CONFIRMING.
4. Persist a SagaInstance.
5. Persist the first Saga command in the transactional outbox.
6. Persist the idempotency response.
7. Commit.

Return:

`202 Accepted`

The frontend then retrieves the status from:

`GET /api/v1/leases/{leaseId}`

The original reservation expiration must no longer release the property once confirmation has validly entered CONFIRMING.

Use a separate persisted Saga timeout and recovery policy.

---

## 6. Saga Orchestration

Implement an orchestrated Saga inside Booking Service.

Do not add a standalone Saga framework unless it clearly reduces complexity.

### Success workflow

1. Confirm request changes Lease to CONFIRMING.
2. Saga sends AuthorizePayment through Kafka.
3. Payment Service authorizes the mock payment and emits PaymentAuthorized.
4. Saga sends PrepareContract.
5. Contract Service prepares a draft and emits ContractPrepared.
6. Saga sends CapturePayment.
7. Payment Service captures the authorized mock payment and emits PaymentCaptured.
8. Booking Service atomically updates Lease to BOOKED and Property to BOOKED.
9. Saga becomes COMPLETED.
10. Booking Service persists LeaseBooked in its outbox.

The final database update must be idempotent and recoverable.

If payment capture succeeded but Booking Service crashes before recording completion, it must resume forward and finalize the booking. Do not automatically release a property with a successfully captured payment simply because a notification was delayed.

### Failure and compensation

Handle:

- Payment authorization rejected
- Contract preparation failed
- Payment capture failed
- Kafka temporarily unavailable
- Duplicate Kafka messages
- Out-of-order or late responses
- Service crashes during a Saga
- Saga timeout
- Compensation failure

Examples:

- Payment authorization fails: release property.
- Contract preparation fails: void authorization and release property.
- Payment capture fails: void prepared contract, void authorization where possible, and release property.
- Payment already captured but later compensation is genuinely required: issue an idempotent refund and reconcile before considering the workflow fully compensated.

Compensation must be durable and retryable.

Do not release Property while a payment or contract side effect is unresolved unless the workflow's recovery policy makes that release safe.

### Property release safety

Every release operation must verify:

- Property status is RESERVED.
- Property activeLeaseId matches the Lease being compensated.
- Lease is in an allowed state for release.

Update Property and Lease atomically.

A delayed compensation for Lease 501 must never release a property now owned by Lease 502.

---

## 7. Transactional Outbox and Kafka

Each service must save outgoing messages to its own outbox table within the same local transaction as its business state changes.

Implement a simple reliable outbox publisher.

Requirements:

- Publish committed outbox events to Kafka.
- Mark them published only after successful broker acknowledgement.
- Retry failed publications.
- Support multiple publisher instances safely.
- Use a reasonable batch size and backoff.
- Accept at-least-once publication.
- Preserve correlation IDs and message IDs.

Use the relevant aggregate ID as the Kafka message key to preserve ordering where it matters.

For inventory-related Lease operations, key by property ID when property-level ordering is required.

Kafka consumers must:

- Detect duplicate messages.
- Persist message processing records in their own database.
- Update business state and record outgoing events in one local transaction.
- Acknowledge messages only after successful commit.
- Handle retries safely.
- Avoid applying late events to invalid Saga states.

Do not assume Kafka exactly-once semantics automatically make external PostgreSQL side effects exactly once.

Use clear event/command names, such as:

- PropertyReserved
- LeaseConfirmationRequested
- AuthorizePayment
- PaymentAuthorized
- PaymentAuthorizationFailed
- PrepareContract
- ContractPrepared
- ContractPreparationFailed
- CapturePayment
- PaymentCaptured
- ReleaseProperty
- LeaseBooked
- LeaseCancelled

Use one consistent event envelope containing messageId, correlationId, causationId, aggregateId, eventType, occurredAt, and payload.

---

## 8. Reservation Expiration

A RESERVED Lease expires after 15 minutes unless confirmation has begun successfully.

Implement a scheduled expiration worker.

It must:

1. Identify expired RESERVED leases.
2. Check the current Lease and Property ownership.
3. Transition Lease to EXPIRED.
4. Release Property to AVAILABLE.
5. Persist an outbox event.
6. Commit all changes atomically.

Handle races between expiration and confirmation using optimistic locking and guarded transitions.

The expiration worker must be safe when several application instances run simultaneously.

A CONFIRMING lease is managed by the Saga timeout policy, not by the original reservation-hold expiration worker.

Use database-consistent time semantics for deadline checks where correctness depends on the boundary.

---

## 9. REST API Design

Implement versioned REST endpoints.

| Method | Endpoint                         | Purpose                            |
| ------ | -------------------------------- | ---------------------------------- |
| GET    | `/api/v1/properties`             | List properties                    |
| GET    | `/api/v1/properties/{id}`        | Property details and availability  |
| POST   | `/api/v1/properties/{id}/leases` | Reserve property                   |
| GET    | `/api/v1/leases/{id}`            | Lease details and current status   |
| POST   | `/api/v1/leases/{id}/confirm`    | Start confirmation Saga            |
| POST   | `/api/v1/leases/{id}/cancel`     | Cancel an unconfirmed reservation  |
| GET    | `/api/v1/leases/{id}/timeline`   | Read Saga progress for the website |

Property creation and failure-injection controls must be admin/demo-only, not unrestricted public operations.

Use:

- DTOs for API requests and responses
- Bean Validation
- Consistent API error responses using RFC 9457 Problem Details
- Correct HTTP semantics
- Clear authentication and ownership enforcement

Important statuses:

- `200`: successful read or completed replay
- `201`: lease created
- `202`: Saga accepted
- `400`: invalid request
- `401/403`: authentication/authorization error
- `404`: resource not found or inaccessible
- `409`: business or concurrency conflict
- `422`: idempotency key reused with different request identity
- `429`: rate limited
- `503`: transient admission or dependency failure

For completed idempotent retries, replay the original stored successful status and response body, even if the Lease's current state has subsequently changed.

The client uses GET to retrieve the latest resource state.

---

## 10. Redis Distributed Locking

Implement a reusable Redis locking component.

Use property-specific keys:

`lock:property:{propertyId}`

Requirements:

- Atomic lock acquisition with TTL.
- Unique lock ownership token.
- Atomic ownership-checked lock release.
- Never delete a lock belonging to another owner.
- Never hold the Redis lock for the entire 15-minute reservation.
- Release only after the synchronous database transaction completes.
- Handle lock contention with bounded retry behavior.
- Do not use Redis as the authoritative reservation state.

Provide a configuration option to benchmark Redis-enabled versus database-only reservation processing.

PostgreSQL correctness must be preserved in both configurations.

If Redis is unavailable, support a documented and tested database-only fallback or an explicit fail-closed policy. Never silently bypass correctness checks.

---

## 11. React Website

Build a polished, responsive dashboard that communicates with the real backend.

### Page A — Property Reservation

Features:

- List sample properties.
- Display current availability.
- Reserve a property.
- Display the hold countdown.
- Confirm or cancel a reservation.
- Show the current Lease status.

### Page B — Concurrency Lab

Allow the user to run controlled demonstrations with:

- 10 requests
- 100 requests
- 1,000 requests

Display actual measured results:

- Successful reservations
- Property-unavailable rejections
- Redis lock contention
- Optimistic-lock conflicts
- Unexpected errors
- Total execution time
- Final Property and Lease database state

Do not hardcode results.

Run large concurrency tests through a controlled backend test endpoint or test runner, not by letting arbitrary public browsers generate unlimited backend requests.

Make the test bounded, authorized, rate-limited, and isolated from other visitors.

### Page C — Saga Visualizer

Show the actual Saga execution:

Reserve → Confirm → Payment → Contract → Booking

Display:

- Current Saga status
- Completed steps
- Pending steps
- Failed steps
- Compensation actions
- Kafka-related event IDs and timestamps
- Final outcome

### Page D — Failure Injection

Provide controlled failure scenarios:

- Payment authorization failure
- Contract preparation failure
- Payment capture failure
- Duplicate Kafka event
- Temporary processing delay
- Consumer recovery

Only enable these controls for isolated demo workflows. Do not allow anonymous visitors to shut down shared infrastructure or disrupt other sessions.

### UI Requirements

Use a clean professional dashboard design with intuitive status indicators and responsive layouts.

Use TanStack Query for API state.

The UI must always show real backend outcomes rather than simulated successes.

Allow visitors to interact with an isolated, server-managed demo session without sharing production credentials.

---

## 12. Testing Requirements

Use JUnit 5, Spring Boot Test, and Testcontainers with real PostgreSQL, Redis, and Kafka dependencies where appropriate.

Tests must verify:

### Concurrency

- 1,000 competing Reserve attempts cannot create multiple live leases.
- Redis-enabled and database-only modes both preserve correctness.
- An early-expiring Redis lock does not cause double booking.
- Concurrent expiration and confirmation produce one valid outcome.
- A stale cancellation cannot release a newer reservation.

### Idempotency

- Same Reserve key returns the original response.
- Same key with different request identity is rejected.
- Concurrent requests with the same key cannot commit duplicates.
- A crash after database commit but before HTTP response does not create another lease on retry.
- Duplicate Confirm requests do not start multiple Sagas.

### Distributed Reliability

- Outbox publication survives application crashes.
- Duplicate Kafka deliveries don't duplicate business effects.
- Failed Saga steps trigger appropriate compensation.
- Late payment events are handled safely.
- Compensation can resume after service restart.
- Saga timeouts do not strand property inventory indefinitely.
- A successful payment capture followed by coordinator failure can recover forward.

Prefer deterministic integration tests and controlled failure injection over tests that depend on arbitrary sleeps.

---

## 13. Deployment and Repository

Use a Maven multi-module monorepo with these deployable components:

- booking-service
- payment-service
- contract-service
- web

Include:

- Dockerfiles
- Docker Compose
- PostgreSQL initialization
- Kafka broker configuration
- Redis
- Flyway migration scripts
- Environment variable examples
- GitHub Actions build/test workflow
- README documentation

The full system must start locally using a documented sequence of commands.

Use separate logical PostgreSQL databases for independent services, even if they share one PostgreSQL container during local development.

Provide deployment guidance for:

- React frontend on Vercel
- Backend services on a cloud VM using Docker Compose
- HTTPS reverse proxy
- Private database, Redis, and Kafka networking
- Secure environment-variable management

Do not expose infrastructure ports or administrative failure-injection endpoints publicly.

---

## 14. Documentation

Create an excellent GitHub README suitable for a Senior Backend Engineer portfolio.

Include:

1. Project overview and engineering goals.
2. Architecture diagram.
3. Database ER diagram.
4. Reserve and Confirm sequence diagrams.
5. Saga state machine.
6. Redis locking explanation.
7. PostgreSQL optimistic-locking explanation.
8. Partial unique index and consistency invariants.
9. Idempotency and failure-retry strategy.
10. Transactional Outbox/Inbox explanation.
11. Instructions to start the entire application.
12. API examples.
13. Concurrency benchmark methodology and real results.
14. Failure-injection demonstrations.
15. Design decisions and trade-offs.

Do not invent benchmark results. Measure and report actual outcomes.

Explain why Redis is used even though PostgreSQL already guarantees reservation correctness.

---

## 15. Implementation Instructions

Implement the actual working project, not only a high-level explanation or pseudocode.

Follow this order:

**Milestone 1 — Domain and persistence**

Create the Booking Service, Property and Lease entities, repositories, Flyway migrations, optimistic locking, and partial unique index.

**Milestone 2 — Reservation API**

Implement Redis admission locking, synchronous Reserve, cancellation, expiration, durable idempotency, and concurrency tests.

**Milestone 3 — Distributed services**

Implement the Payment and Contract mock services, Kafka communication, transactional outbox, and consumer deduplication.

**Milestone 4 — Saga orchestration**

Implement the asynchronous Confirm workflow, Saga state machine, compensation, timeouts, and crash recovery.

**Milestone 5 — Interactive website**

Implement the React property reservation page, Concurrency Lab, Saga Visualizer, and controlled failure demonstrations.

**Milestone 6 — Deployment and verification**

Add Docker Compose, CI/CD, observability, integration tests, and portfolio documentation.

### Engineering Guidelines

- Prefer established Spring Boot patterns.
- Keep domain logic out of controllers.
- Use Spring Data JPA for ordinary persistence.
- Use transactional service boundaries.
- Avoid unnecessary repository abstractions or generic frameworks.
- Do not put business correctness in Redis.
- Do not assume remote calls participate in local database transactions.
- Preserve idempotency across process crashes.
- Handle failures explicitly.
- Use readable code and meaningful names.
- Do not hardcode secrets.
- Write tests for critical state transitions.
- Ensure the application builds and runs.

Work incrementally, verify every milestone, and keep the application in a working state.

**Start with Milestone 1 and implement the complete entity model, database constraints, repositories, and migrations. Then continue in the specified order, reporting completed components, executed tests, and any limitations.**

The final result should be a functional, deployable project that clearly demonstrates high-concurrency inventory reservation and reliable distributed Saga processing.
