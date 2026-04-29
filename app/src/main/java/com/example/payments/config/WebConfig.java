package com.example.payments.config;

import com.example.payments.adapter.in.rest.IdempotencyFilter;
import com.example.payments.application.idempotency.IdempotencyService;
import com.example.payments.domain.port.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WebConfig {

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(
            IdempotencyService service,
            IdempotencyRepository repository,
            Clock clock,
            ObjectMapper objectMapper) {
        var filter = new IdempotencyFilter(service, repository, clock, objectMapper, hostname());
        var reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/v1/payment-intents", "/v1/payment-intents/*");
        reg.setOrder(10);
        return reg;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
