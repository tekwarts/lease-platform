package com.example.leaseplatform.controller;

import com.example.leaseplatform.service.*;
import com.example.leaseplatform.config.*;
import com.example.leaseplatform.dto.Views;
import com.example.leaseplatform.exception.ApiErrors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({DemoController.class, ReservationController.class})
@Import({DemoSecurity.class, ReservationHttpTest.Settings.class, ApiErrors.class})
class ReservationHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean DemoInventory demoInventory;
    @MockitoBean ConcurrencyLab lab;
    @MockitoBean InventoryQueries queries;
    @MockitoBean ReservationService reservations;

    @TestConfiguration
    static class Settings {
        @Bean BookingSettings settings() {
            return new BookingSettings(Duration.ofMinutes(15), true, Duration.ofSeconds(5), true);
        }
    }

    @Test
    void inventoryRequiresAServerCreatedSession() throws Exception {
        mvc.perform(get("/api/v1/properties")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
    }

    @Test
    void bootstrapRequiresCsrfAndSeedsOnlyOnceForTheSession() throws Exception {
        mvc.perform(post("/api/v1/demo/session")).andExpect(status().isForbidden());
        var session = new MockHttpSession();
        var token = token(session);
        mvc.perform(post("/api/v1/demo/session").session(session).header(token.header(), token.value()))
                .andExpect(status().isOk());
        String owner = (String) session.getAttribute(DemoController.OWNER_ATTRIBUTE);
        assertThat(owner).startsWith("demo-");
        mvc.perform(post("/api/v1/demo/session").session(session).header(token.header(), token.value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(owner));
        verify(demoInventory, times(1)).seed(owner);
    }

    @Test
    void reservePreservesTheStoredStatusAndExactJsonAndTakesIdentityFromSession() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute(DemoController.OWNER_ATTRIBUTE, "owner");
        var token = token(session);
        UUID id = UUID.randomUUID(), key = UUID.randomUUID();
        String body = "{\"id\":\"original\",\"status\":\"RESERVED\"}";
        when(reservations.reserve("owner", id, key)).thenReturn(new Views.StoredResponse(201, body, true));
        mvc.perform(post("/api/v1/properties/{id}/leases", id).session(session)
                        .header(token.header(), token.value()).header("Idempotency-Key", key)
                        .header("X-Tenant-Id", "attacker"))
                .andExpect(status().isCreated()).andExpect(content().string(body))
                .andExpect(header().string("Idempotency-Replayed", "true"));
        verify(reservations).reserve("owner", id, key);
    }

    @Test
    void missingKeyIsAProblemDetailsValidationError() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute(DemoController.OWNER_ATTRIBUTE, "owner");
        var token = token(session);
        mvc.perform(post("/api/v1/properties/{id}/leases", UUID.randomUUID()).session(session)
                        .header(token.header(), token.value()))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        verifyNoInteractions(reservations);
    }

    @Test
    void arbitraryLabLoadIsRejectedBeforeTheRunner() throws Exception {
        var session = new MockHttpSession();
        session.setAttribute(DemoController.OWNER_ATTRIBUTE, "owner");
        var token = token(session);
        mvc.perform(post("/api/v1/demo/runs").session(session).header(token.header(), token.value())
                        .contentType("application/json").content("{\"attempts\":999999,\"redisEnabled\":false}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RUN_SIZE"));
        verifyNoInteractions(lab);
    }

    private Token token(MockHttpSession session) throws Exception {
        var response = mvc.perform(get("/api/v1/csrf").session(session)).andExpect(status().isOk()).andReturn();
        var node = json.readTree(response.getResponse().getContentAsString());
        return new Token(node.get("headerName").asString(), node.get("token").asString());
    }

    record Token(String header, String value) {}
}
