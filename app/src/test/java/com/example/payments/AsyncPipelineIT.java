package com.example.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.model.PaymentState;
import com.example.payments.domain.port.PaymentIntentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

@SpringBootTest(
        properties = {
            "outbox.queue-url=http://localhost:4566/000000000000/payment-events.fifo",
            "aws.sqs.endpoint-override=http://localhost:4566",
            "aws.region=eu-west-1",
            "outbox.poll-interval-ms=200",
            "consumer.poll-interval-ms=200",
            "consumer.wait-time-seconds=1",
            "processor.stub.auth-success-rate=1.0",
            "processor.stub.decline-rate=0.0",
            "processor.stub.timeout-rate=0.0",
            "processor.stub.error-rate=0.0",
            "processor.stub.hang-rate=0.0",
            "processor.stub.base-latency-ms=10"
        })
@AutoConfigureMockMvc
@EnabledIf("dependenciesReachable")
class AsyncPipelineIT {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PaymentIntentRepository repository;
    @Autowired private SqsClient sqs;

    @Value("${outbox.queue-url}")
    private String queueUrl;

    @SuppressWarnings("unused")
    static boolean dependenciesReachable() {
        return reachable("localhost", 5432) && reachable("localhost", 4566);
    }

    private static boolean reachable(String host, int port) {
        try (var sock = new Socket()) {
            sock.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void clean() {
        sqs.createQueue(
                CreateQueueRequest.builder()
                        .queueName("payment-events.fifo")
                        .attributes(
                                Map.of(
                                        QueueAttributeName.FIFO_QUEUE, "true",
                                        QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "false"))
                        .build());
        jdbc.execute(
                "TRUNCATE TABLE refunds, captures, payment_attempts, payment_intents,"
                        + " idempotency_records, outbox, processed_events RESTART IDENTITY CASCADE");
        drainQueue();
    }

    private void drainQueue() {
        while (true) {
            var resp =
                    sqs.receiveMessage(
                            ReceiveMessageRequest.builder()
                                    .queueUrl(queueUrl)
                                    .maxNumberOfMessages(10)
                                    .waitTimeSeconds(0)
                                    .build());
            if (resp.messages().isEmpty()) {
                return;
            }
            for (var msg : resp.messages()) {
                sqs.deleteMessage(
                        DeleteMessageRequest.builder()
                                .queueUrl(queueUrl)
                                .receiptHandle(msg.receiptHandle())
                                .build());
            }
        }
    }

    @Test
    void confirm_drives_authorization_through_outbox_and_consumer() throws Exception {
        MvcResult createRes =
                mvc.perform(
                                post("/v1/payment-intents")
                                        .header("Idempotency-Key", "k-create-" + UUID.randomUUID())
                                        .header("Merchant-Id", "acme")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"amountMinorUnits\":1500,\"currency\":\"EUR\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        String idStr =
                objectMapper
                        .readTree(createRes.getResponse().getContentAsString())
                        .get("id")
                        .asText();
        var id = new PaymentIntentId(UUID.fromString(idStr));

        mvc.perform(
                        post("/v1/payment-intents/{id}/confirm", idStr)
                                .header("Idempotency-Key", "k-conf-" + UUID.randomUUID())
                                .header("Merchant-Id", "acme")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"paymentMethod\":{\"token\":\"tok_visa\",\"type\":\"CARD\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PROCESSING"));

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(
                        () -> {
                            var intent = repository.findById(id).orElseThrow();
                            assertThat(intent.state()).isEqualTo(PaymentState.AUTHORIZED);
                        });
    }
}
