package com.example.leaseplatform.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.OffsetDateTime;

@Component
public class DatabaseTime {
    private final JdbcTemplate jdbc;

    public DatabaseTime(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Read near the guarded transition, inside its transaction, not before admission. */
    public Instant now() {
        return jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class).toInstant();
    }
}
