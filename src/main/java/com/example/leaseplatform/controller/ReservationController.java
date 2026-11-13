package com.example.leaseplatform.controller;

import com.example.leaseplatform.service.*;
import com.example.leaseplatform.dto.Views.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ReservationController {
    private final InventoryQueries inventory;
    private final ReservationService reservations;

    public ReservationController(InventoryQueries inventory, ReservationService reservations) {
        this.inventory = inventory;
        this.reservations = reservations;
    }

    @GetMapping("/properties")
    List<PropertyView> properties(Principal principal) { return inventory.properties(principal.getName()); }

    @GetMapping("/properties/{id}")
    PropertyView property(Principal principal, @PathVariable UUID id) { return inventory.property(principal.getName(), id); }

    @GetMapping("/leases/{id}")
    LeaseView lease(Principal principal, @PathVariable UUID id) { return inventory.lease(principal.getName(), id); }

    @PostMapping("/properties/{id}/leases")
    ResponseEntity<String> reserve(Principal principal, @PathVariable UUID id,
                                  @RequestHeader("Idempotency-Key") UUID key) {
        return response(reservations.reserve(principal.getName(), id, key));
    }

    @PostMapping("/leases/{id}/cancel")
    ResponseEntity<String> cancel(Principal principal, @PathVariable UUID id,
                                 @RequestHeader("Idempotency-Key") UUID key) {
        return response(reservations.cancel(principal.getName(), id, key));
    }

    private ResponseEntity<String> response(StoredResponse response) {
        return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Replayed", Boolean.toString(response.replayed()))
                .cacheControl(CacheControl.noStore()).body(response.body());
    }
}
