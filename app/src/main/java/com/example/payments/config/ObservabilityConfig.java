package com.example.payments.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

/**
 * Provides the {@link CloudWatchAsyncClient} bean only when {@code aws.cloudwatch.enabled=true}.
 * Spring Boot's auto-configuration then activates the {@code CloudWatchMeterRegistry} (the dep is
 * already on the classpath via {@code micrometer-registry-cloudwatch2}). Local and test runs leave
 * the bean absent and stay on the in-memory {@code SimpleMeterRegistry}.
 */
@Configuration
@ConditionalOnProperty(name = "aws.cloudwatch.enabled", havingValue = "true")
public class ObservabilityConfig {

    @Bean
    CloudWatchAsyncClient cloudWatchAsyncClient(@Value("${aws.region:eu-west-1}") String region) {
        return CloudWatchAsyncClient.builder().region(Region.of(region)).build();
    }
}
