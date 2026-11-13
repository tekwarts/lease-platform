package com.example.leaseplatform.repository;

import com.example.leaseplatform.domain.Property;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
import java.util.List;
import java.util.Optional;

public interface PropertyRepository extends JpaRepository<Property, UUID> {
    Optional<Property> findByIdAndDemoOwnerId(UUID id, String owner);
    List<Property> findByDemoOwnerIdAndLabPropertyFalseOrderByCreatedAtAsc(String owner);
}
