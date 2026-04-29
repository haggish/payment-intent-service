package com.example.payments.adapter.in.rest;

import com.example.payments.application.idempotency.IdempotencyService;
import com.example.payments.application.idempotency.IdempotencyService.Outcome;
import com.example.payments.domain.model.IdempotencyKey;
import com.example.payments.domain.model.MerchantId;
import com.example.payments.domain.port.IdempotencyRepository;
import com.example.payments.domain.port.IdempotencyRepository.CompletedResponse;
import com.example.payments.domain.port.IdempotencyRepository.ReservationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String PATH_PREFIX = "/v1/payment-intents";

    private final IdempotencyService service;
    private final IdempotencyRepository repository;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final String nodeId;

    public IdempotencyFilter(
            IdempotencyService service,
            IdempotencyRepository repository,
            Clock clock,
            ObjectMapper objectMapper,
            String nodeId) {
        this.service = service;
        this.repository = repository;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.nodeId = nodeId;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        if (!"POST".equalsIgnoreCase(req.getMethod())
                || !req.getRequestURI().startsWith(PATH_PREFIX)) {
            chain.doFilter(req, res);
            return;
        }

        String key = req.getHeader("Idempotency-Key");
        String merchant = req.getHeader("Merchant-Id");
        if (isBlank(key) || isBlank(merchant)) {
            writeProblem(res, 400, "Missing Idempotency-Key or Merchant-Id header");
            return;
        }

        byte[] body = req.getInputStream().readAllBytes();
        String hash = sha256Hex(body);
        var bufferedReq = new CachedBodyRequest(req, body);

        var ctx =
                new ReservationContext(
                        req.getMethod(),
                        req.getRequestURI(),
                        hash,
                        nodeId,
                        clock.instant().plus(TTL));
        var merchantId = new MerchantId(merchant);
        var idempotencyKey = new IdempotencyKey(key);

        Outcome outcome =
                service.resolve(
                        merchantId,
                        idempotencyKey,
                        req.getMethod(),
                        req.getRequestURI(),
                        hash,
                        ctx);

        switch (outcome) {
            case Outcome.Replay r -> writeReplay(res, r.response());
            case Outcome.InProgress ip -> writeProblem(res, 409, "Request already in progress");
            case Outcome.RequestMismatch rm ->
                    writeProblem(res, 422, "Same idempotency key, different request body");
            case Outcome.Proceed p -> proceed(merchantId, idempotencyKey, bufferedReq, res, chain);
        }
    }

    private void proceed(
            MerchantId merchant,
            IdempotencyKey key,
            HttpServletRequest req,
            HttpServletResponse res,
            FilterChain chain)
            throws IOException, ServletException {
        var wrapped = new ContentCachingResponseWrapper(res);
        try {
            chain.doFilter(req, wrapped);
            int status = wrapped.getStatus();
            byte[] body = wrapped.getContentAsByteArray();
            wrapped.copyBodyToResponse();
            if (status >= 200 && status < 300) {
                String bodyJson =
                        body.length == 0 ? "null" : new String(body, StandardCharsets.UTF_8);
                repository.complete(merchant, key, new CompletedResponse(status, "{}", bodyJson));
            } else {
                safeRelease(merchant, key);
            }
        } catch (RuntimeException | ServletException | IOException e) {
            safeRelease(merchant, key);
            try {
                wrapped.copyBodyToResponse();
            } catch (RuntimeException ignored) {
                // best-effort
            }
            throw e;
        }
    }

    private void safeRelease(MerchantId merchant, IdempotencyKey key) {
        try {
            repository.releaseInProgress(merchant, key);
        } catch (RuntimeException ignored) {
            // best-effort; row will expire via TTL
        }
    }

    private void writeReplay(HttpServletResponse res, CompletedResponse stored) throws IOException {
        res.setStatus(stored.status());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setHeader("Idempotent-Replay", "true");
        res.getWriter().write(stored.bodyJson());
    }

    private void writeProblem(HttpServletResponse res, int status, String detail)
            throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter()
                .write(
                        objectMapper.writeValueAsString(
                                Map.of(
                                        "type", "about:blank",
                                        "title", detail,
                                        "status", status,
                                        "detail", detail)));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String sha256Hex(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            var sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest req, byte[] body) {
            super(req);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream backing = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return backing.read();
                }

                @Override
                public boolean isFinished() {
                    return backing.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener l) {}
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
