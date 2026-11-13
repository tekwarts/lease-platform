package com.example.leaseplatform;

import com.example.leaseplatform.domain.*;
import com.example.leaseplatform.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL tests: no H2 substitute and no silent skip when Docker is missing. */
@SpringBootTest
@Testcontainers
class BookingPersistenceIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired PropertyRepository properties;
    @Autowired LeaseRepository leases;
    @Autowired IdempotencyRequestRepository idempotency;
    @Autowired SagaInstanceRepository sagas;
    @Autowired OutboxEventRepository outbox;
    @Autowired ProcessedMessageRepository inbox;
    @Autowired DatabaseTime databaseTime;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;

    private UUID createProperty() {
        return transactions.execute(tx -> properties.saveAndFlush(new Property("Harbour Loft", databaseTime.now())).getId());
    }

    private UUID reserve(UUID propertyId) {
        return transactions.execute(tx -> {
            Property property = properties.findById(propertyId).orElseThrow();
            Lease lease = property.reserve("tenant", databaseTime.now(), Duration.ofMinutes(15));
            leases.saveAndFlush(lease);
            return lease.getId();
        });
    }

    @Test
    void flywayMigratesAndHibernateValidatesAllEntities() {
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where success", Long.class))
                .isEqualTo(2L);
        UUID propertyId = createProperty();
        UUID leaseId = reserve(propertyId);
        assertThat(properties.findById(propertyId).orElseThrow().getActiveLeaseId()).isEqualTo(leaseId);
        assertThat(leases.findByIdAndTenantId(leaseId, "tenant")).isPresent();
        assertThat(leases.findByIdAndTenantId(leaseId, "someone-else")).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = LeaseStatus.class, names = {"RESERVED", "CONFIRMING", "COMPENSATING", "BOOKED"})
    void partialIndexRejectsTwoLiveLeasesEvenWhenBypassingTheApplication(LeaseStatus status) {
        UUID propertyId = createProperty();
        UUID first = reserve(propertyId);
        jdbc.update("update lease set status = ? where id = ?", status.name(), first);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> insertRawLease(propertyId, status)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_lease_one_active_per_property");
        assertThat(leases.findByPropertyIdOrderByCreatedAtDesc(propertyId)).hasSize(1);
    }

    private void insertRawLease(UUID propertyId, LeaseStatus status) {
        jdbc.update("""
                insert into lease (id, property_id, tenant_id, status, hold_expires_at, version, created_at, updated_at)
                values (?, ?, 'other-tenant', ?, clock_timestamp() + interval '15 minutes', 0,
                        clock_timestamp(), clock_timestamp())
                """, UUID.randomUUID(), propertyId, status.name());
    }

    @Test
    void historicalLeasesDoNotBlockANewReservation() {
        UUID propertyId = createProperty();
        UUID firstId = reserve(propertyId);
        transactions.executeWithoutResult(tx -> {
            Property property = properties.findById(propertyId).orElseThrow();
            Lease first = leases.findById(firstId).orElseThrow();
            property.cancel(first);
            // Flush the old live lease before inserting another into the partial unique index.
            leases.flush();
            Lease second = property.reserve("next-tenant", databaseTime.now(), Duration.ofMinutes(15));
            leases.saveAndFlush(second);
        });
        assertThat(leases.findByPropertyIdOrderByCreatedAtDesc(propertyId)).hasSize(2);
        assertThat(leases.findById(firstId).orElseThrow().getStatus()).isEqualTo(LeaseStatus.CANCELLED);
    }

    @Test
    void stalePropertyUpdateFailsOptimisticLocking() {
        UUID propertyId = createProperty();
        try (EntityManager first = entityManagerFactory.createEntityManager();
             EntityManager second = entityManagerFactory.createEntityManager()) {
            first.getTransaction().begin();
            second.getTransaction().begin();
            Property a = first.find(Property.class, propertyId);
            Property b = second.find(Property.class, propertyId);
            // Second transaction has observed availability before the first commits.
            first.persist(a.reserve("first", databaseTime.now(), Duration.ofMinutes(15)));
            first.getTransaction().commit();
            b.reserve("second", databaseTime.now(), Duration.ofMinutes(15));
            assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);
            second.getTransaction().rollback();
        }
        assertThat(leases.findByPropertyIdOrderByCreatedAtDesc(propertyId)).hasSize(1);
    }

    @Test
    void staleLeaseTransitionFailsOptimisticLocking() {
        UUID propertyId = createProperty();
        UUID leaseId = reserve(propertyId);
        try (EntityManager first = entityManagerFactory.createEntityManager();
             EntityManager second = entityManagerFactory.createEntityManager()) {
            first.getTransaction().begin();
            second.getTransaction().begin();
            Lease staleLease = second.find(Lease.class, leaseId);
            Property staleProperty = second.find(Property.class, propertyId);
            Property owner = first.find(Property.class, propertyId);
            owner.beginConfirmation(first.find(Lease.class, leaseId), databaseTime.now());
            first.getTransaction().commit();
            staleProperty.cancel(staleLease);
            assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);
            second.getTransaction().rollback();
        }
        assertThat(leases.findById(leaseId).orElseThrow().getStatus()).isEqualTo(LeaseStatus.CONFIRMING);
        assertThat(properties.findById(propertyId).orElseThrow().getStatus()).isEqualTo(PropertyStatus.RESERVED);
    }

    @Test
    void rollbackRemovesLeaseOutboxAndIdempotencyTogether() {
        UUID propertyId = createProperty();
        UUID key = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> {
            Property property = properties.findById(propertyId).orElseThrow();
            Instant now = databaseTime.now();
            leases.save(property.reserve("tenant", now, Duration.ofMinutes(15)));
            outbox.save(new OutboxEvent(propertyId, correlationId, null, "PropertyReserved", "{}", now));
            idempotency.save(new IdempotencyRequest("tenant", "RESERVE", key, "a".repeat(64), 201, "{}", now));
            properties.flush();
            throw new IllegalStateException("simulated failure before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(properties.findById(propertyId).orElseThrow().getStatus()).isEqualTo(PropertyStatus.AVAILABLE);
        assertThat(leases.findByPropertyIdOrderByCreatedAtDesc(propertyId)).isEmpty();
        assertThat(idempotency.findByUserIdAndOperationAndIdempotencyKey("tenant", "RESERVE", key)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where correlation_id = ?",
                Long.class, correlationId)).isZero();
    }

    @Test
    void allSupportingRecordsRoundTripAndEnforceUniqueIdentities() {
        UUID propertyId = createProperty();
        UUID leaseId = reserve(propertyId);
        UUID key = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID[] ids = transactions.execute(tx -> {
            Instant now = databaseTime.now();
            SagaInstance saga = sagas.save(new SagaInstance(leaseId, now, now.plusSeconds(120)));
            OutboxEvent event = outbox.save(new OutboxEvent(propertyId, saga.getId(), messageId,
                    "AuthorizePayment", "{\"leaseId\":\"" + leaseId + "\"}", now));
            inbox.save(new ProcessedMessage(messageId, "booking", now));
            idempotency.save(new IdempotencyRequest("tenant", "RESERVE", key, "a".repeat(64),
                    201, "{\"leaseId\":\"" + leaseId + "\"}", now));
            return new UUID[]{saga.getId(), event.getId()};
        });
        assertThat(sagas.findById(ids[0]).orElseThrow().getVersion()).isNotNull();
        assertThat(outbox.findById(ids[1]).orElseThrow().getPayload()).contains(leaseId.toString());
        assertThat(inbox.findById(new ProcessedMessageId(messageId, "booking"))).isPresent();
        assertThat(idempotency.findByUserIdAndOperationAndIdempotencyKey("tenant", "RESERVE", key)
                .orElseThrow().getResponseStatus()).isEqualTo(201);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx ->
                idempotency.saveAndFlush(new IdempotencyRequest("tenant", "RESERVE", key,
                        "a".repeat(64), 201, "{}", databaseTime.now()))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> jdbc.update("""
                insert into processed_message values (?, 'booking', clock_timestamp())
                """, messageId))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx ->
                sagas.saveAndFlush(new SagaInstance(leaseId, databaseTime.now(), Instant.now().plusSeconds(300)))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ownershipPointerCannotReferenceAnotherPropertysLease() {
        UUID first = createProperty();
        UUID second = createProperty();
        UUID secondLease = reserve(second);
        // The deferred ownership constraint must be checked at COMMIT, not just at flush.
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> jdbc.update("""
                update property set status = 'RESERVED', active_lease_id = ? where id = ?
                """, secondLease, first))).isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("fk_property_active_lease");
        assertThat(properties.findById(first).orElseThrow().getStatus()).isEqualTo(PropertyStatus.AVAILABLE);
    }
}
