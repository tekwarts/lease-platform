package com.example.leaseplatform.controller;

import com.example.leaseplatform.service.*;
import com.example.leaseplatform.config.BookingSettings;
import com.example.leaseplatform.exception.ApiException;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DemoController {
    public static final String OWNER_ATTRIBUTE = "leaseflow.demoOwner";
    private final BookingSettings settings;
    private final DemoInventory inventory;
    private final ConcurrencyLab lab;

    public DemoController(BookingSettings settings, DemoInventory inventory, ConcurrencyLab lab) {
        this.settings = settings;
        this.inventory = inventory;
        this.lab = lab;
    }

    @GetMapping("/csrf")
    CsrfView csrf(CsrfToken token) { return new CsrfView(token.getHeaderName(), token.getToken()); }

    @PostMapping("/demo/session")
    SessionView session(HttpSession session) {
        if (!settings.demoEnabled()) throw ApiException.notFound();
        synchronized (session) {
            String owner = (String) session.getAttribute(OWNER_ATTRIBUTE);
            if (owner == null) {
                owner = "demo-" + UUID.randomUUID();
                inventory.seed(owner);
                session.setAttribute(OWNER_ATTRIBUTE, owner);
            }
            return new SessionView(owner, settings.redisEnabled());
        }
    }

    @PostMapping("/demo/runs")
    ConcurrencyLab.Result run(Principal principal, HttpSession session, @Valid @RequestBody RunRequest request) {
        if (!settings.demoEnabled()) throw ApiException.notFound();
        if (request.attempts() != 10 && request.attempts() != 100 && request.attempts() != 1000) {
            throw new ApiException(400, "INVALID_RUN_SIZE", "Choose 10, 100, or 1000 attempts.");
        }
        synchronized (session) {
            Long last = (Long) session.getAttribute("leaseflow.lastRun");
            if (last != null && System.nanoTime() - last < java.time.Duration.ofSeconds(5).toNanos()) {
                throw new ApiException(429, "RUN_RATE_LIMIT", "Wait five seconds between runs.");
            }
            session.setAttribute("leaseflow.lastRun", System.nanoTime());
        }
        return lab.run(principal.getName(), request.attempts(), request.redisEnabled());
    }

    record CsrfView(String headerName, String token) {}
    record SessionView(String id, boolean redisEnabled) {}
    record RunRequest(@NotNull Integer attempts, boolean redisEnabled) {}
}
