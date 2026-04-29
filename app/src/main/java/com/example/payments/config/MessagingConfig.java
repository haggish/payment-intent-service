package com.example.payments.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Publishes the SqsClient only when {@code outbox.queue-url} is set to a non-empty value. Lets
 * dev/test boot without AWS credentials or LocalStack.
 *
 * <p>If {@code aws.sqs.endpoint-override} is set (LocalStack, dev), the override is applied and
 * dummy static credentials are used. Otherwise the default credentials/region chain is used (real
 * AWS, including Fargate task role).
 */
@Configuration
@ConditionalOnExpression("'${outbox.queue-url:}' != ''")
public class MessagingConfig {

    @Bean
    SqsClient sqsClient(
            @Value("${aws.sqs.endpoint-override:}") String endpointOverride,
            @Value("${aws.region:eu-west-1}") String region) {
        var builder = SqsClient.builder().region(Region.of(region));
        if (!endpointOverride.isBlank()) {
            builder.endpointOverride(URI.create(endpointOverride))
                    .credentialsProvider(
                            StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create("test", "test")));
        }
        return builder.build();
    }
}
