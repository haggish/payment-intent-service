package com.example.payments.domain.port;

import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentIntentId;
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

    class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(PaymentIntentId id, long expectedVersion) {
            super("stale update for intent %s at version %d".formatted(id, expectedVersion));
        }
    }
}
