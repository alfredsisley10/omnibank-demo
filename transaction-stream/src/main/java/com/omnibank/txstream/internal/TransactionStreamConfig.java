package com.omnibank.txstream.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnibank.shared.kafka.AppMapSpanRecorder;
import com.omnibank.shared.kafka.KafkaTopics;
import com.omnibank.shared.kafka.TracedKafkaPublisher;
import com.omnibank.shared.kafka.testing.InMemoryKafkaBus;
import com.omnibank.shared.nosql.DocumentStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring config wiring the streaming-transaction stack.
 *
 * <p>The orchestrator is always present; the Kafka publishing path is
 * either the real {@link TracedKafkaPublishAdapter} (when Kafka is
 * enabled and a {@link TracedKafkaPublisher} bean exists) or an
 * {@link InMemoryKafkaPublishAdapter} that fans out through the same
 * trace context end-to-end without touching a broker.</p>
 */
@Configuration
public class TransactionStreamConfig {

    @Bean
    @ConditionalOnMissingBean(AppMapSpanRecorder.class)
    public AppMapSpanRecorder defaultAppMapSpanRecorder() {
        return new AppMapSpanRecorder();
    }

    @Bean
    @ConditionalOnMissingBean(InMemoryKafkaBus.class)
    public InMemoryKafkaBus inMemoryKafkaBus() {
        return new InMemoryKafkaBus();
    }

    @Bean
    @ConditionalOnProperty(name = "omnibank.kafka.enabled", havingValue = "true")
    public KafkaPublishAdapter tracedKafkaPublishAdapter(
            TracedKafkaPublisher publisher,
            @Value("${omnibank.kafka.publish-timeout-ms:5000}") long timeoutMs) {
        return new TracedKafkaPublishAdapter(publisher, timeoutMs);
    }

    @Bean
    @ConditionalOnMissingBean(KafkaPublishAdapter.class)
    public KafkaPublishAdapter inMemoryKafkaPublishAdapter(InMemoryKafkaBus bus,
                                                           ObjectMapper mapper) {
        return new InMemoryKafkaPublishAdapter(bus, mapper);
    }

    @Bean
    public StreamingTransactionConsumer streamingTransactionConsumer(
            DocumentStore documents,
            AppMapSpanRecorder spanRecorder,
            ObjectMapper mapper) {
        return new StreamingTransactionConsumer(documents, spanRecorder, mapper);
    }

    /**
     * When the in-memory bus is in play, register the consumer against
     * the payment-events topic so the producer→consumer hop is wired
     * end-to-end without a broker.
     */
    @Bean
    public Object inMemoryConsumerWiring(InMemoryKafkaBus bus,
                                         StreamingTransactionConsumer consumer) {
        bus.register(KafkaTopics.PAYMENT_EVENTS, (record, ctx) -> consumer.onRecord(record));
        return new Object();
    }
}
