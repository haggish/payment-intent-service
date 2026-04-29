package com.example.payments.adapter.in.rest;

import com.example.payments.application.usecase.CancelPaymentIntent;
import com.example.payments.application.usecase.CapturePayment;
import com.example.payments.application.usecase.ConfirmPaymentIntent;
import com.example.payments.application.usecase.CreatePaymentIntent;
import com.example.payments.application.usecase.CreateRefund;
import com.example.payments.domain.exception.PaymentIntentNotFoundException;
import com.example.payments.domain.model.IdempotencyKey;
import com.example.payments.domain.model.MerchantId;
import com.example.payments.domain.model.Money;
import com.example.payments.domain.model.PaymentIntent;
import com.example.payments.domain.model.PaymentIntentId;
import com.example.payments.domain.model.PaymentMethod;
import com.example.payments.domain.port.PaymentIntentRepository;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payment-intents")
public class PaymentIntentController {

    private final CreatePaymentIntent createUseCase;
    private final ConfirmPaymentIntent confirmUseCase;
    private final CapturePayment captureUseCase;
    private final CancelPaymentIntent cancelUseCase;
    private final CreateRefund refundUseCase;
    private final PaymentIntentRepository repository;

    public PaymentIntentController(
            CreatePaymentIntent createUseCase,
            ConfirmPaymentIntent confirmUseCase,
            CapturePayment captureUseCase,
            CancelPaymentIntent cancelUseCase,
            CreateRefund refundUseCase,
            PaymentIntentRepository repository) {
        this.createUseCase = createUseCase;
        this.confirmUseCase = confirmUseCase;
        this.captureUseCase = captureUseCase;
        this.cancelUseCase = cancelUseCase;
        this.refundUseCase = refundUseCase;
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<PaymentIntentResponse> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("Merchant-Id") String merchantId,
            @RequestBody @Valid CreatePaymentIntentRequest body) {
        PaymentIntent intent =
                createUseCase.execute(
                        new MerchantId(merchantId),
                        Money.of(body.amountMinorUnits(), body.currency()),
                        new IdempotencyKey(idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(PaymentIntentResponse.from(intent));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<PaymentIntentResponse> confirm(
            @PathVariable UUID id,
            @RequestBody(required = false) @Valid ConfirmPaymentIntentRequest body) {
        Optional<PaymentMethod> method =
                Optional.ofNullable(body)
                        .map(ConfirmPaymentIntentRequest::paymentMethod)
                        .map(p -> new PaymentMethod(p.token(), p.type()));
        PaymentIntent intent = confirmUseCase.execute(new PaymentIntentId(id), method);
        return ResponseEntity.ok(PaymentIntentResponse.from(intent));
    }

    @PostMapping("/{id}/capture")
    public ResponseEntity<PaymentIntentResponse> capture(
            @PathVariable UUID id, @RequestBody @Valid CapturePaymentRequest body) {
        PaymentIntent intent =
                captureUseCase.execute(
                        new PaymentIntentId(id),
                        Money.of(body.amountMinorUnits(), body.currency()));
        return ResponseEntity.ok(PaymentIntentResponse.from(intent));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<PaymentIntentResponse> cancel(
            @PathVariable UUID id, @RequestBody @Valid CancelPaymentIntentRequest body) {
        PaymentIntent intent = cancelUseCase.execute(new PaymentIntentId(id), body.reason());
        return ResponseEntity.ok(PaymentIntentResponse.from(intent));
    }

    @PostMapping("/{id}/refund")
    public ResponseEntity<PaymentIntentResponse> refund(
            @PathVariable UUID id, @RequestBody @Valid RefundRequest body) {
        PaymentIntent intent =
                refundUseCase.execute(
                        new PaymentIntentId(id),
                        body.captureIndex(),
                        Money.of(body.amountMinorUnits(), body.currency()));
        return ResponseEntity.ok(PaymentIntentResponse.from(intent));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentIntentResponse> get(@PathVariable UUID id) {
        var intentId = new PaymentIntentId(id);
        PaymentIntent intent =
                repository
                        .findById(intentId)
                        .orElseThrow(() -> new PaymentIntentNotFoundException(intentId));
        return ResponseEntity.ok(PaymentIntentResponse.from(intent));
    }
}
