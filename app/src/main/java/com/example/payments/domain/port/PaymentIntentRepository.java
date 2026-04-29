package com.example.payments.domain.port;

import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentIntentId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Domain-side port for {@link PaymentIntent} persistence. Implementations live in the adapter
 * layer.
 */
public interface PaymentIntentRepository {

    Optional<PaymentIntent> findById(PaymentIntentId id);

    /**
     * Persists the aggregate. Implementations must use optimistic locking via the version field —
     * stale writes throw {@link OptimisticLockException}.
     */
    void save(PaymentIntent intent);

    /**
     * Find intents stuck in {@code PROCESSING} state with {@code updated_at} older than the given
     * cutoff. Used by reconciliation to discover authorizations the processor may have completed
     * after the consumer received an {@code Unknown} outcome.
     */
    List<PaymentIntentId> findStuckProcessing(Instant olderThan, int limit);

    class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(PaymentIntentId id, long expectedVersion) {
            super("stale update for intent %s at version %d".formatted(id, expectedVersion));
        }
    }
}
