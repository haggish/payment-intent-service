package com.example.payments.config;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

/**
 * Publishes the service's business metrics to CloudWatch when {@code aws.cloudwatch.enabled=true}
 * (set by the ECS task definition). Local and test runs stay on Boot's in-memory registry.
 *
 * <p>Spring Boot has no CloudWatch auto-configuration, so the registry is wired here. Two
 * registries are defined and Boot combines them into a composite:
 *
 * <ul>
 *   <li>a {@link SimpleMeterRegistry} that keeps every meter (JVM, HTTP, Hikari, ...) visible on
 *       {@code /actuator/metrics};
 *   <li>a {@link CloudWatchMeterRegistry} restricted to {@code payments.*} and {@code outbox.*} —
 *       the meters the dashboard and alarms read. CloudWatch bills per metric, and exporting
 *       everything (e.g. {@code http.server.requests} percentiles per URI/status) would turn a
 *       near-free deployment into hundreds of custom metrics.
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "aws.cloudwatch.enabled", havingValue = "true")
@EnableConfigurationProperties(ObservabilityConfig.CloudWatchProperties.class)
public class ObservabilityConfig {

    @ConfigurationProperties("aws.cloudwatch")
    record CloudWatchProperties(String namespace, Duration step) {}

    static final MeterFilter EXPORTED_TO_CLOUDWATCH =
            MeterFilter.denyUnless(
                    id ->
                            id.getName().startsWith("payments.")
                                    || id.getName().startsWith("outbox."));

    @Bean
    @ConditionalOnMissingBean
    CloudWatchAsyncClient cloudWatchAsyncClient(@Value("${aws.region:eu-west-1}") String region) {
        return CloudWatchAsyncClient.builder().region(Region.of(region)).build();
    }

    @Bean
    CloudWatchMeterRegistry cloudWatchMeterRegistry(
            CloudWatchAsyncClient client, CloudWatchProperties properties) {
        CloudWatchConfig config =
                new CloudWatchConfig() {
                    @Override
                    public String get(String key) {
                        return null;
                    }

                    @Override
                    public String namespace() {
                        return properties.namespace();
                    }

                    @Override
                    public Duration step() {
                        return properties.step();
                    }
                };
        CloudWatchMeterRegistry registry =
                new CloudWatchMeterRegistry(config, Clock.SYSTEM, client);
        registry.config().meterFilter(EXPORTED_TO_CLOUDWATCH);
        return registry;
    }

    /** Boot's own simple registry backs off once any registry bean exists, so declare it. */
    @Bean
    SimpleMeterRegistry simpleMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}
