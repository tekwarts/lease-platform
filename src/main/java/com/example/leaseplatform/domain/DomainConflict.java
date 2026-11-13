package com.example.leaseplatform.domain;

public class DomainConflict extends RuntimeException {
    public DomainConflict(String message) { super(message); }
}
