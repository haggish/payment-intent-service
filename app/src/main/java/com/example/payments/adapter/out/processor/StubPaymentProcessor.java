package com.example.payments.adapter.out.processor;

import com.example.payments.domain.port.PaymentProcessor;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Stub processor calibrated to exercise every failure mode the production code is designed to
 * handle. Failure rates are tunable via {@link StubProcessorProperties} for chaos testing.
 *
 * <p>Crucially, even when the stub returns {@code Unknown} to the caller, it remembers the outcome
 * it actually decided — so {@link #lookup} can give the correct reconciliation answer. This mirrors
 * how real processors behave: the operation completes server-side even if the client never gets a
 * definitive response.
 */
@Component
@Profile("!real-processor")
public class StubPaymentProcessor implements PaymentProcessor {

    private final StubProcessorProperties props;
    private final ConcurrentHashMap<String, ProcessorStatus> ledger = new ConcurrentHashMap<>();

    public StubPaymentProcessor(StubProcessorProperties props) {
        this.props = props;
    }

    @Override
    public AuthorizationResult authorize(AuthorizationRequest request) {
        sleep(props.baseLatencyMs());
        double r = ThreadLocalRandom.current().nextDouble();

        if (r < props.hangRate()) {
            sleep(30_000);
        }

        String key = request.idempotencyKey().toString();
        if (r < props.errorRate()) {
            throw new ProcessorUnavailableException("simulated 5xx");
        }
        if (r < props.errorRate() + props.timeoutRate()) {
            // Authorization actually succeeds server-side, but the caller doesn't know
            String reference = "pi_stub_" + UUID.randomUUID();
            ledger.put(key, new ProcessorStatus("AUTHORIZED", reference));
            return new AuthorizationResult.Unknown("simulated timeout");
        }
        if (r < props.errorRate() + props.timeoutRate() + props.declineRate()) {
            ledger.put(key, new ProcessorStatus("DECLINED", null));
            return new AuthorizationResult.Declined(
                    DeclineReason.CARD_DECLINED, "simulated decline");
        }
        String reference = "pi_stub_" + UUID.randomUUID();
        ledger.put(key, new ProcessorStatus("AUTHORIZED", reference));
        return new AuthorizationResult.Authorized(reference, Instant.now());
    }

    @Override
    public CaptureResult capture(CaptureRequest request) {
        sleep(props.baseLatencyMs());
        // TODO: similar failure-mode coverage for capture
        return new CaptureResult.Captured("cap_stub_" + UUID.randomUUID(), Instant.now());
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        sleep(props.baseLatencyMs());
        // TODO: similar failure-mode coverage for refund
        return new RefundResult.Refunded("re_stub_" + UUID.randomUUID(), Instant.now());
    }

    @Override
    public Optional<ProcessorStatus> lookup(String idempotencyKey) {
        return Optional.ofNullable(ledger.get(idempotencyKey));
    }

    private void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static class ProcessorUnavailableException extends RuntimeException {
        public ProcessorUnavailableException(String message) {
            super(message);
        }
    }

    @ConfigurationProperties("processor.stub")
    public record StubProcessorProperties(
            double authSuccessRate,
            double declineRate,
            double timeoutRate,
            double errorRate,
            double hangRate,
            long baseLatencyMs) {}
}
