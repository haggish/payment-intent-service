package com.example.payments.adapter.in.rest;

import com.example.payments.application.usecase.CreatePaymentIntent;
import com.example.payments.domain.model.IdempotencyKey;
import com.example.payments.domain.model.MerchantId;
import com.example.payments.domain.model.Money;
import com.example.payments.domain.model.PaymentIntent;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/payment-intents")
public class PaymentIntentController {

    private final CreatePaymentIntent createUseCase;

    public PaymentIntentController(CreatePaymentIntent createUseCase) {
        this.createUseCase = createUseCase;
    }

    @PostMapping
    public ResponseEntity<PaymentIntentResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("Merchant-Id") String merchantId,
            @RequestBody CreatePaymentIntentRequest body) {

        // TODO: idempotency interceptor handles this header before reaching the controller
        // TODO: validation, request hash computation, error mapping
        PaymentIntent intent =
                createUseCase.execute(
                        new MerchantId(merchantId),
                        Money.of(body.amountMinorUnits(), body.currency()),
                        new IdempotencyKey(idempotencyKey));

        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentIntentResponse.from(intent));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<PaymentIntentResponse> confirm(@PathVariable String id) {
        // TODO: implement
        throw new UnsupportedOperationException("not implemented");
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<PaymentIntentResponse> capture(@PathVariable String id) {
        // TODO: implement
        throw new UnsupportedOperationException("not implemented");
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<PaymentIntentResponse> cancel(@PathVariable String id) {
        // TODO: implement
        throw new UnsupportedOperationException("not implemented");
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentIntentResponse> get(@PathVariable String id) {
        // TODO: implement
        throw new UnsupportedOperationException("not implemented");
    }
}
