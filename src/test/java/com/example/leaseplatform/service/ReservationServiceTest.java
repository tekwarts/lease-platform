package com.example.leaseplatform.service;

import com.example.leaseplatform.exception.ApiException;
import com.example.leaseplatform.config.BookingSettings;
import com.example.leaseplatform.domain.*;
import com.example.leaseplatform.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReservationServiceTest {
    private final PropertyRepository properties = mock(PropertyRepository.class);
    private final LeaseRepository leases = mock(LeaseRepository.class);
    private final IdempotencyRequestRepository records = mock(IdempotencyRequestRepository.class);
    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final DatabaseTime clock = mock(DatabaseTime.class);
    private final AdmissionLock admission = mock(AdmissionLock.class);
    private final AdmissionLock.Ticket ticket = mock(AdmissionLock.Ticket.class);
    private final Instant now = Instant.parse("2026-11-12T12:00:00Z");
    private ReservationService service;

    @BeforeEach
    void setUp() {
        service = new ReservationService(properties, leases, records, outbox, transactions, clock,
                new JsonMapper(), admission, new BookingSettings(Duration.ofMinutes(15), true, Duration.ofSeconds(5), true));
        when(clock.now()).thenReturn(now);
        when(records.findByUserIdAndOperationAndIdempotencyKey(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(admission.acquire(any(), anyBoolean())).thenReturn(ticket);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    @Test
    void replayUsesStoredBytesWithoutAcquiringRedisOrReadingCurrentInventory() throws Exception {
        UUID propertyId = UUID.randomUUID(), key = UUID.randomUUID();
        String body = "{\"status\":\"RESERVED\",\"original\":true}";
        var saved = new IdempotencyRequest("tenant", "RESERVE", key, hash("RESERVE:" + propertyId), 201, body, now);
        when(records.findByUserIdAndOperationAndIdempotencyKey("tenant", "RESERVE", key)).thenReturn(Optional.of(saved));
        var replay = service.reserve("tenant", propertyId, key);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(body);
        assertThat(replay.replayed()).isTrue();
        verifyNoInteractions(admission, properties, leases, transactions, outbox);
    }

    @Test
    void changedRequestIdentityIsRejectedBeforeAnyMutation() throws Exception {
        UUID key = UUID.randomUUID();
        var saved = new IdempotencyRequest("tenant", "RESERVE", key, hash("RESERVE:" + UUID.randomUUID()), 201, "{}", now);
        when(records.findByUserIdAndOperationAndIdempotencyKey("tenant", "RESERVE", key)).thenReturn(Optional.of(saved));
        assertThatThrownBy(() -> service.reserve("tenant", UUID.randomUUID(), key))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(422));
        verifyNoInteractions(admission, properties, leases, transactions, outbox);
    }

    @Test
    void successStoresResponseWithBusinessChangesAndReleasesRedisAfterTransactionReturns() {
        Property property = Property.demo("Loft", now, "tenant", false);
        when(properties.findByIdAndDemoOwnerId(property.getId(), "tenant")).thenReturn(Optional.of(property));
        var response = service.reserve("tenant", property.getId(), UUID.randomUUID());
        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body()).contains(property.getActiveLeaseId().toString());
        assertThat(property.getStatus()).isEqualTo(PropertyStatus.RESERVED);
        verify(leases).saveAndFlush(any(Lease.class));
        verify(records).saveAndFlush(argThat(record -> record.getResponseBody().equals(response.body()) && record.getResponseStatus() == 201));
        verify(outbox).save(argThat(event -> event.getEventType().equals("PropertyReserved")));
        var order = inOrder(admission, transactions, ticket);
        order.verify(admission).acquire(property.getId(), true);
        order.verify(transactions).execute(any());
        order.verify(ticket).close();
    }

    @Test
    void rejectedReservationCreatesNoOutboxOrIdempotencyRecordAndReleasesAdmission() {
        Property property = Property.demo("Loft", now, "tenant", false);
        Lease active = property.reserve("someone", now, Duration.ofMinutes(15));
        when(properties.findByIdAndDemoOwnerId(property.getId(), "tenant")).thenReturn(Optional.of(property));
        when(leases.findById(property.getActiveLeaseId())).thenReturn(Optional.of(active));
        assertThatThrownBy(() -> service.reserve("tenant", property.getId(), UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(409));
        verifyNoInteractions(outbox);
        verify(records, never()).saveAndFlush(any());
        verify(ticket).close();
    }

    @Test
    void inaccessiblePropertyDoesNotCreateALease() {
        assertThatThrownBy(() -> service.reserve("intruder", UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(404));
        verifyNoInteractions(leases, outbox);
        verify(records, never()).saveAndFlush(any());
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
