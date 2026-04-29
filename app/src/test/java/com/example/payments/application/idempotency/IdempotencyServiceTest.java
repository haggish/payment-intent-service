package com.example.payments.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payments.application.idempotency.IdempotencyService.Outcome;
import com.example.payments.domain.model.IdempotencyKey;
import com.example.payments.domain.model.MerchantId;
import com.example.payments.domain.port.IdempotencyRepository;
import com.example.payments.domain.port.IdempotencyRepository.CompletedResponse;
import com.example.payments.domain.port.IdempotencyRepository.IdempotencyRecord;
import com.example.payments.domain.port.IdempotencyRepository.IdempotencyRecord.State;
import com.example.payments.domain.port.IdempotencyRepository.ReservationContext;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IdempotencyServiceTest {

    private static final MerchantId MERCHANT = new MerchantId("acme");
    private static final IdempotencyKey KEY = new IdempotencyKey("test-key-1234");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryIdempotencyRepository repo = new InMemoryIdempotencyRepository();
    private final IdempotencyService service = new IdempotencyService(repo);

    private static ReservationContext ctx(String hash) {
        return new ReservationContext("POST", "/v1/payment-intents", hash, "node-1", T0);
    }

    @Test
    void unseen_key_proceeds() {
        var outcome = service.resolve(MERCHANT, KEY, "POST", "/v1/x", "hash-A", ctx("hash-A"));
        assertThat(outcome).isInstanceOf(Outcome.Proceed.class);
    }

    @Test
    void completed_request_replays_stored_response() {
        service.resolve(MERCHANT, KEY, "POST", "/v1/x", "hash-A", ctx("hash-A"));
        repo.complete(MERCHANT, KEY, new CompletedResponse(201, "{}", "{\"id\":\"abc\"}"));

        var outcome = service.resolve(MERCHANT, KEY, "POST", "/v1/x", "hash-A", ctx("hash-A"));
        assertThat(outcome).isInstanceOf(Outcome.Replay.class);
        assertThat(((Outcome.Replay) outcome).response().status()).isEqualTo(201);
    }

    @Test
    void in_progress_returns_in_progress() {
        service.resolve(MERCHANT, KEY, "POST", "/v1/x", "hash-A", ctx("hash-A"));
        var outcome = service.resolve(MERCHANT, KEY, "POST", "/v1/x", "hash-A", ctx("hash-A"));
        assertThat(outcome).isInstanceOf(Outcome.InProgress.class);
    }

    @Test
    void same_key_different_body_hash_returns_request_mismatch() {
        service.resolve(MERCHANT, KEY, "POST", "/v1/x", "hash-A", ctx("hash-A"));
        var outcome = service.resolve(MERCHANT, KEY, "POST", "/v1/x", "hash-B", ctx("hash-B"));
        assertThat(outcome).isInstanceOf(Outcome.RequestMismatch.class);
    }

    @Test
    void prior_failure_allows_retry() {
        service.resolve(MERCHANT, KEY, "POST", "/v1/x", "hash-A", ctx("hash-A"));
        repo.markFailed(MERCHANT, KEY);
        var outcome = service.resolve(MERCHANT, KEY, "POST", "/v1/x", "hash-A", ctx("hash-A"));
        assertThat(outcome).isInstanceOf(Outcome.Proceed.class);
    }

    /** Test fake. Models the real ON CONFLICT DO NOTHING semantics with a HashMap put-if-absent. */
    private static class InMemoryIdempotencyRepository implements IdempotencyRepository {

        private final Map<String, IdempotencyRecord> store = new HashMap<>();

        private static String compositeKey(MerchantId merchantId, IdempotencyKey key) {
            return merchantId.value() + "|" + key.value();
        }

        @Override
        public boolean tryReserve(MerchantId merchantId, IdempotencyKey key, ReservationContext c) {
            var k = compositeKey(merchantId, key);
            if (store.containsKey(k)) {
                return false;
            }
            store.put(
                    k,
                    new IdempotencyRecord(
                            c.requestHash(),
                            State.IN_PROGRESS,
                            c.expiresAt(),
                            Optional.empty(),
                            c.expiresAt()));
            return true;
        }

        @Override
        public Optional<IdempotencyRecord> find(MerchantId merchantId, IdempotencyKey key) {
            return Optional.ofNullable(store.get(compositeKey(merchantId, key)));
        }

        @Override
        public void complete(MerchantId merchantId, IdempotencyKey key, CompletedResponse r) {
            var k = compositeKey(merchantId, key);
            var existing = Objects.requireNonNull(store.get(k), "no record to complete");
            store.put(
                    k,
                    new IdempotencyRecord(
                            existing.requestHash(),
                            State.COMPLETED,
                            existing.lockedAt(),
                            Optional.of(r),
                            existing.expiresAt()));
        }

        @Override
        public void releaseInProgress(MerchantId merchantId, IdempotencyKey key) {
            store.remove(compositeKey(merchantId, key));
        }

        void markFailed(MerchantId merchantId, IdempotencyKey key) {
            var k = compositeKey(merchantId, key);
            var existing = Objects.requireNonNull(store.get(k), "no record");
            store.put(
                    k,
                    new IdempotencyRecord(
                            existing.requestHash(),
                            State.FAILED,
                            existing.lockedAt(),
                            Optional.empty(),
                            existing.expiresAt()));
        }

        @Override
        public int deleteExpired() {
            return 0;
        }
    }
}
