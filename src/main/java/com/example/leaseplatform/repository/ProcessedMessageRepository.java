package com.example.leaseplatform.repository;

import com.example.leaseplatform.domain.ProcessedMessage;
import com.example.leaseplatform.domain.ProcessedMessageId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, ProcessedMessageId> {}
