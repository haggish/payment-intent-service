package com.example.payments.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.payments.application.metrics.PaymentMetrics;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.jvm.JvmMetricsAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataResponse;

class ObservabilityConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(
                                    MetricsAutoConfiguration.class,
                                    CompositeMeterRegistryAutoConfiguration.class,
                                    SimpleMetricsExportAutoConfiguration.class,
                                    JvmMetricsAutoConfiguration.class))
                    .withUserConfiguration(ObservabilityConfig.class)
                    .withPropertyValues(
                            "aws.cloudwatch.namespace=Payments/Application",
                            "aws.cloudwatch.step=1m");

    @Test
    void disabled_by_default_leaves_only_the_simple_registry() {
        runner.run(
                ctx -> {
                    assertThat(ctx).doesNotHaveBean(CloudWatchMeterRegistry.class);
                    assertThat(ctx.getBean(MeterRegistry.class))
                            .isInstanceOf(SimpleMeterRegistry.class);
                });
    }

    @Test
    void enabled_keeps_all_meters_locally_but_exports_only_business_metrics() {
        CloudWatchAsyncClient client = mock(CloudWatchAsyncClient.class);
        when(client.putMetricData(any(PutMetricDataRequest.class)))
                .thenReturn(
                        CompletableFuture.completedFuture(PutMetricDataResponse.builder().build()));

        runner.withPropertyValues("aws.cloudwatch.enabled=true")
                .withBean(CloudWatchAsyncClient.class, () -> client)
                .run(
                        ctx -> {
                            MeterRegistry registry = ctx.getBean(MeterRegistry.class);
                            assertThat(registry).isInstanceOf(CompositeMeterRegistry.class);

                            new PaymentMetrics(registry); // registers the real payments.* counters
                            registry.counter("payments.created").increment();
                            Gauge.builder("outbox.unpublished_count", () -> 3).register(registry);
                            Gauge.builder("outbox.oldest_unpublished_age_seconds", () -> 42)
                                    .register(registry);

                            // JVM meters are still available to /actuator/metrics...
                            assertThat(
                                            ctx.getBean(SimpleMeterRegistry.class)
                                                    .find("jvm.threads.live")
                                                    .gauge())
                                    .isNotNull();

                            ctx.getBean(CloudWatchMeterRegistry.class).close(); // forces a publish

                            ArgumentCaptor<PutMetricDataRequest> sent =
                                    ArgumentCaptor.forClass(PutMetricDataRequest.class);
                            verify(client, atLeastOnce()).putMetricData(sent.capture());
                            List<PutMetricDataRequest> requests = sent.getAllValues();
                            assertThat(requests)
                                    .extracting(PutMetricDataRequest::namespace)
                                    .containsOnly("Payments/Application");

                            Set<String> names =
                                    requests.stream()
                                            .flatMap(r -> r.metricData().stream())
                                            .map(MetricDatum::metricName)
                                            .collect(Collectors.toSet());
                            // ...but only business metrics reach CloudWatch, under the exact
                            // names (Micrometer appends .count / .value) and without dimensions
                            // that infrastructure/lib/observability/{dashboard,alarms}.ts query.
                            assertThat(names)
                                    .allMatch(
                                            n ->
                                                    n.startsWith("payments.")
                                                            || n.startsWith("outbox."))
                                    .contains(
                                            "payments.created.count",
                                            "payments.confirmed.count",
                                            "payments.authorized.count",
                                            "payments.captured.count",
                                            "payments.declined.count",
                                            "payments.failed.count",
                                            "payments.refunded.count",
                                            "outbox.unpublished_count.value",
                                            "outbox.oldest_unpublished_age_seconds.value");
                            assertThat(requests)
                                    .flatExtracting(PutMetricDataRequest::metricData)
                                    .allSatisfy(d -> assertThat(d.dimensions()).isEmpty());
                        });
    }
}
