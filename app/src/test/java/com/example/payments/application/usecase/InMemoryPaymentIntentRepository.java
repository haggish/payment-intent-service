package com.example.payments.application.usecase;

import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.model.PaymentState;
import com.example.payments.domain.port.PaymentIntentRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test fake. Plain map; no concurrency story. */
class InMemoryPaymentIntentRepository implements PaymentIntentRepository {

    private final Map<PaymentIntentId, PaymentIntent> store = new HashMap<>();
    int saveCount = 0;

    @Override
    public Optional<PaymentIntent> findById(PaymentIntentId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public void save(PaymentIntent intent) {
        store.put(intent.id(), intent);
        saveCount++;
    }

    @Override
    public List<PaymentIntentId> findStuckProcessing(Instant olderThan, int limit) {
        return store.values().stream()
                .filter(
                        i ->
                                i.state() == PaymentState.PROCESSING
                                        && i.updatedAt().isBefore(olderThan))
                .limit(limit)
                .map(PaymentIntent::id)
                .toList();
    }
}
