package com.example.payments.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf("postgresReachable")
class PaymentIntentControllerIT {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    @SuppressWarnings("unused")
    static boolean postgresReachable() {
        try (var sock = new Socket()) {
            sock.connect(new InetSocketAddress("localhost", 5432), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void clean() {
        jdbc.execute(
                "TRUNCATE TABLE refunds, captures, payment_attempts, payment_intents,"
                        + " idempotency_records, outbox, processed_events RESTART IDENTITY"
                        + " CASCADE");
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    @Test
    void create_then_confirm_then_get() throws Exception {
        var createBody = "{\"amountMinorUnits\":1500,\"currency\":\"EUR\"}";
        MvcResult createRes =
                mvc.perform(
                                post("/v1/payment-intents")
                                        .header("Idempotency-Key", key())
                                        .header("Merchant-Id", "acme")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(createBody))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.id").exists())
                        .andExpect(jsonPath("$.amountMinorUnits").value(1500))
                        .andExpect(jsonPath("$.currency").value("EUR"))
                        .andExpect(jsonPath("$.state").value("REQUIRES_PAYMENT_METHOD"))
                        .andReturn();
        String id =
                objectMapper
                        .readTree(createRes.getResponse().getContentAsString())
                        .get("id")
                        .asText();

        var confirmBody = "{\"paymentMethod\":{\"token\":\"tok_visa\",\"type\":\"CARD\"}}";
        mvc.perform(
                        post("/v1/payment-intents/{id}/confirm", id)
                                .header("Idempotency-Key", key())
                                .header("Merchant-Id", "acme")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(confirmBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PROCESSING"));

        mvc.perform(get("/v1/payment-intents/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PROCESSING"));
    }

    @Test
    void idempotent_replay_returns_stored_response() throws Exception {
        var body = "{\"amountMinorUnits\":1000,\"currency\":\"EUR\"}";
        String key = key();
        MvcResult first =
                mvc.perform(
                                post("/v1/payment-intents")
                                        .header("Idempotency-Key", key)
                                        .header("Merchant-Id", "acme")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        String firstBody = first.getResponse().getContentAsString();

        MvcResult second =
                mvc.perform(
                                post("/v1/payment-intents")
                                        .header("Idempotency-Key", key)
                                        .header("Merchant-Id", "acme")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andExpect(header().string("Idempotent-Replay", "true"))
                        .andReturn();
        assertThat(objectMapper.readTree(second.getResponse().getContentAsString()))
                .isEqualTo(objectMapper.readTree(firstBody));
    }

    @Test
    void same_key_different_body_returns_422() throws Exception {
        String key = key();
        mvc.perform(
                        post("/v1/payment-intents")
                                .header("Idempotency-Key", key)
                                .header("Merchant-Id", "acme")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amountMinorUnits\":1000,\"currency\":\"EUR\"}"))
                .andExpect(status().isCreated());

        mvc.perform(
                        post("/v1/payment-intents")
                                .header("Idempotency-Key", key)
                                .header("Merchant-Id", "acme")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amountMinorUnits\":2000,\"currency\":\"EUR\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void missing_idempotency_headers_returns_400() throws Exception {
        mvc.perform(
                        post("/v1/payment-intents")
                                .header("Merchant-Id", "acme")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amountMinorUnits\":1000,\"currency\":\"EUR\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknown_intent_returns_404() throws Exception {
        mvc.perform(get("/v1/payment-intents/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void capture_before_authorization_returns_409() throws Exception {
        MvcResult res =
                mvc.perform(
                                post("/v1/payment-intents")
                                        .header("Idempotency-Key", key())
                                        .header("Merchant-Id", "acme")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"amountMinorUnits\":1000,\"currency\":\"EUR\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String id =
                objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(
                        post("/v1/payment-intents/{id}/capture", id)
                                .header("Idempotency-Key", key())
                                .header("Merchant-Id", "acme")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amountMinorUnits\":1000,\"currency\":\"EUR\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void invalid_amount_returns_400_with_field_errors() throws Exception {
        mvc.perform(
                        post("/v1/payment-intents")
                                .header("Idempotency-Key", key())
                                .header("Merchant-Id", "acme")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amountMinorUnits\":0,\"currency\":\"EUR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("amountMinorUnits"));
    }

    @Test
    void malformed_json_returns_400() throws Exception {
        mvc.perform(
                        post("/v1/payment-intents")
                                .header("Idempotency-Key", key())
                                .header("Merchant-Id", "acme")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bad_uuid_path_returns_400() throws Exception {
        mvc.perform(get("/v1/payment-intents/not-a-uuid")).andExpect(status().isBadRequest());
    }
}
