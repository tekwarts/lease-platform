package com.example.leaseplatform.service;

import com.example.leaseplatform.dto.Views.*;
import com.example.leaseplatform.exception.ApiException;
import com.example.leaseplatform.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class InventoryQueries {
    private final PropertyRepository properties;
    private final LeaseRepository leases;

    public InventoryQueries(PropertyRepository properties, LeaseRepository leases) {
        this.properties = properties;
        this.leases = leases;
    }

    public List<PropertyView> properties(String owner) {
        return properties.findByDemoOwnerIdAndLabPropertyFalseOrderByCreatedAtAsc(owner).stream().map(PropertyView::of)
                .toList();
    }

    public PropertyView property(String owner, UUID id) {
        return PropertyView.of(properties.findByIdAndDemoOwnerId(id, owner).orElseThrow(ApiException::notFound));
    }

    public LeaseView lease(String tenant, UUID id) {
        return LeaseView.of(leases.findByIdAndTenantId(id, tenant).orElseThrow(ApiException::notFound));
    }
}
