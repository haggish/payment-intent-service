package com.example.payments.application.idempotency;

import com.example.payments.domain.model.IdempotencyKey;
import com.example.payments.domain.model.MerchantId;
import com.example.payments.domain.port.IdempotencyRepository;
import com.example.payments.domain.port.IdempotencyRepository.IdempotencyRecord;

/**
 * Implements the five distinct outcomes when a request arrives with an idempotency key:
 *
 * <ol>
 *   <li>Key not seen before — caller proceeds with handler
 *   <li>Same key, completed — replay the stored response
 *   <li>Same key, in progress — return 409 Conflict (or wait, depending on policy)
 *   <li>Same key, different request body — return 422 Unprocessable Entity (client misuse)
 *   <li>Same key, prior failure — depends on policy; current implementation allows reprocessing
 * </ol>
 *
 * <p>Race-free reservation uses {@code INSERT ... ON CONFLICT DO NOTHING} in the underlying
 * repository.
 */
public class IdempotencyService {

    private final IdempotencyRepository repository;

    public IdempotencyService(IdempotencyRepository repository) {
        this.repository = repository;
    }

    public Outcome resolve(
            MerchantId merchantId,
            IdempotencyKey key,
            String method,
            String path,
            String requestHash,
            IdempotencyRepository.ReservationContext reservation) {

        boolean reserved = repository.tryReserve(merchantId, key, reservation);
        if (reserved) {
            return new Outcome.Proceed();
        }

        IdempotencyRecord existing =
                repository
                        .find(merchantId, key)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "key contention: reservation failed but record not found"));

        if (!existing.requestHash().equals(requestHash)) {
            return new Outcome.RequestMismatch();
        }

        return switch (existing.state()) {
            case COMPLETED ->
                    existing.response()
                            .<Outcome>map(Outcome.Replay::new)
                            .orElseGet(Outcome.Proceed::new);
            case IN_PROGRESS -> new Outcome.InProgress();
            case FAILED -> new Outcome.Proceed();
        };
    }

    public sealed interface Outcome {
        record Proceed() implements Outcome {}

        record Replay(IdempotencyRepository.CompletedResponse response) implements Outcome {}

        record InProgress() implements Outcome {}

        record RequestMismatch() implements Outcome {}
    }
}
