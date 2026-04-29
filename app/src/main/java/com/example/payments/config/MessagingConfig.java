package com.example.payments.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * Publishes the SqsClient only when {@code outbox.queue-url} is set to a non-empty value. Lets
 * dev/test boot without AWS credentials or LocalStack.
 */
@Configuration
@ConditionalOnExpression("'${outbox.queue-url:}' != ''")
public class MessagingConfig {

    @Bean
    SqsClient sqsClient() {
        return SqsClient.create();
    }
}
