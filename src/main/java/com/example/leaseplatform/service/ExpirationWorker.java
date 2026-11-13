package com.example.leaseplatform.service;

import com.example.leaseplatform.domain.*;
import com.example.leaseplatform.repository.*;
import org.slf4j.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Component
public class ExpirationWorker {
    private static final Logger log = LoggerFactory.getLogger(ExpirationWorker.class);
    private final LeaseRepository leases;
    private final PropertyRepository properties;
    private final DatabaseTime clock;
    private final TransactionTemplate transactions;
    private final ReservationService reservations;

    public ExpirationWorker(LeaseRepository leases, PropertyRepository properties, DatabaseTime clock,
                            TransactionTemplate transactions, ReservationService reservations) {
        this.leases = leases;
        this.properties = properties;
        this.clock = clock;
        this.transactions = transactions;
        this.reservations = reservations;
    }

    @Scheduled(fixedDelayString = "${leaseflow.expiration-interval:5000}")
    public void scan() {
        try {
            var candidates = leases.findByStatusAndHoldExpiresAtLessThanEqualOrderByHoldExpiresAtAsc(
                    LeaseStatus.RESERVED, clock.now(), PageRequest.of(0, 50));
            for (Lease lease : candidates) {
                try {
                    expire(lease.getId());
                } catch (OptimisticLockingFailureException | DomainConflict error) {
                    log.debug("Lease {} changed during expiration; next scan will recheck it", lease.getId());
                }
            }
        } catch (org.springframework.dao.DataAccessException error) {
            log.warn("Expiration scan could not access PostgreSQL; retrying on the next scan");
        }
    }

    public boolean expire(UUID id) {
        return Boolean.TRUE.equals(transactions.execute(tx -> {
            Lease lease = leases.findById(id).orElse(null);
            if (lease == null || lease.getStatus() != LeaseStatus.RESERVED) return false;
            Property property = properties.findById(lease.getPropertyId()).orElseThrow();
            var now = clock.now();
            if (!lease.isHoldExpired(now)) return false;
            property.expire(lease, now);
            reservations.emit(property.getId(), id, "LeaseExpired", lease, now);
            leases.flush();
            return true;
        }));
    }
}
