package com.example.leaseplatform.exception;

import com.example.leaseplatform.domain.DomainConflict;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import java.net.URI;

@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException error) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status()), error.getMessage());
        problem.setType(URI.create("urn:leaseflow:error:" + error.code().toLowerCase(java.util.Locale.ROOT)));
        problem.setProperty("code", error.code());
        HttpHeaders headers = new HttpHeaders();
        if (error.status() == 503) headers.set("Retry-After", "1");
        if (error.status() == 429) headers.set("Retry-After", "5");
        return new ResponseEntity<>(problem, headers, HttpStatusCode.valueOf(error.status()));
    }

    @ExceptionHandler(DomainConflict.class)
    ResponseEntity<ProblemDetail> conflict(DomainConflict error) {
        return api(new ApiException(409, "PROPERTY_UNAVAILABLE", error.getMessage()));
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<ProblemDetail> databaseUnavailable(DataAccessResourceFailureException error) {
        return api(new ApiException(503, "DATABASE_UNAVAILABLE", "Database unavailable. Retry using the same idempotency key."));
    }
}
