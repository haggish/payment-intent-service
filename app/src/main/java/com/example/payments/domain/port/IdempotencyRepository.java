package com.example.payments.domain.port;

import com.example.payments.domain.model.IdempotencyKey;
import com.example.payments.domain.model.MerchantId;
import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRepository {

    /**
     * Attempts to reserve an idempotency key. Implementations use {@code INSERT ... ON CONFLICT DO
     * NOTHING} for race-free reservation.
     *
     * @return true if reserved (caller proceeds with handler), false if already exists
     */
    boolean tryReserve(MerchantId merchantId, IdempotencyKey key, ReservationContext context);

    Optional<IdempotencyRecord> find(MerchantId merchantId, IdempotencyKey key);

    void complete(MerchantId merchantId, IdempotencyKey key, CompletedResponse response);

    void releaseInProgress(MerchantId merchantId, IdempotencyKey key);

    /** Cleans up records whose {@code expires_at} is in the past. */
    int deleteExpired();

    record ReservationContext(
            String method, String path, String requestHash, String lockedBy, Instant expiresAt) {}

    record CompletedResponse(int status, String headersJson, String bodyJson) {}

    record IdempotencyRecord(
            String requestHash,
            State state,
            Instant lockedAt,
            Optional<CompletedResponse> response,
            Instant expiresAt) {

        public enum State {
            IN_PROGRESS,
            COMPLETED,
            FAILED
        }
    }
}
