package com.omnibank.shared.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end producer→consumer test using a Testcontainers Kafka. Tagged
 * {@code docker} so default {@code ./gradlew test} skips it; run with
 * {@code -Dkafka.tests=true} or {@code -PtestTag=docker} when Docker is
 * available.
 */
@Tag("docker")
@Testcontainers
class TracedKafkaIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    private KafkaTemplate<String, String> template;
    private TracedKafkaPublisher publisher;

    @BeforeEach
    void setUp() {
        Map<String, Object> producerProps = new HashMap<>();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));
        publisher = new TracedKafkaPublisher(template, new ObjectMapper(), false);
    }

    @AfterEach
    void tearDown() {
        if (template != null) template.destroy();
    }

    @Test
    void producer_consumer_round_trip_preserves_trace_headers() throws Exception {
        String topic = "tcit-" + UUID.randomUUID();

        KafkaTraceContext ctx = KafkaTraceContext.newRoot()
                .withRecording("rec-tc")
                .withBaggage("env", "tc");

        publisher.publishSync(topic, "key-1", Map.of("hello", "world"), ctx, 5_000L);

        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "tcit-group");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            consumer.subscribe(List.of(topic));
            ConsumerRecords<String, String> records = pollUntilNonEmpty(consumer);
            assertThat(records.count()).isGreaterThan(0);
            ConsumerRecord<String, String> rec = records.iterator().next();
            KafkaTraceContext extracted = TracedConsumerInterceptor.extract(rec);
            assertThat(extracted.traceId()).isEqualTo(ctx.traceId());
            assertThat(extracted.recordingId()).contains("rec-tc");
            assertThat(extracted.baggage()).containsEntry("env", "tc");
        }
    }

    private ConsumerRecords<String, String> pollUntilNonEmpty(KafkaConsumer<String, String> consumer) {
        long deadline = System.currentTimeMillis() + 30_000L;
        ConsumerRecords<String, String> records = ConsumerRecords.empty();
        while (records.isEmpty() && System.currentTimeMillis() < deadline) {
            records = consumer.poll(Duration.ofMillis(500));
        }
        return records;
    }
}
