package com.omnibank.shared.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Auto-configuration for the Omnibank Kafka stack.
 *
 * <p>Activates only when {@code omnibank.kafka.enabled=true}; tests that
 * don't need the real broker leave the flag unset and operate against
 * the in-memory {@link com.omnibank.shared.kafka.testing.FakeKafkaTemplate}
 * instead. (When you want the broker, point the harness at a Testcontainers
 * Kafka and flip the flag — see {@code KafkaIntegrationTest}.)</p>
 */
@Configuration
@ConditionalOnProperty(name = "omnibank.kafka.enabled", havingValue = "true")
@EnableKafka
public class KafkaConfig {

    @Value("${omnibank.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${omnibank.kafka.consumer-group:omnibank-default}")
    private String consumerGroup;

    @Value("${omnibank.kafka.audit-mirror:true}")
    private boolean auditMirror;

    @Value("${omnibank.kafka.span-buffer-size:1024}")
    private int spanBufferSize;

    @Bean
    public ProducerFactory<String, String> kafkaProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }

    @Bean
    public ConsumerFactory<String, String> kafkaConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> cf) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.setConcurrency(2);
        return factory;
    }

    @Bean
    public TracedKafkaPublisher tracedKafkaPublisher(KafkaTemplate<String, String> template,
                                                     ObjectMapper mapper) {
        return new TracedKafkaPublisher(template, mapper, auditMirror);
    }

    @Bean
    public AppMapSpanRecorder appMapSpanRecorder() {
        return new AppMapSpanRecorder(spanBufferSize);
    }

    /**
     * Topics provisioned automatically when the broker is reachable. The
     * defaults match the small-scale demo broker; production overrides
     * partition / replication factor via property file.
     */
    @Bean
    public List<NewTopic> omnibankTopicDefinitions(
            @Value("${omnibank.kafka.partitions:3}") int partitions,
            @Value("${omnibank.kafka.replication-factor:1}") short replicationFactor) {
        var topics = new java.util.ArrayList<NewTopic>();
        for (String topic : KafkaTopics.all()) {
            topics.add(new NewTopic(topic, partitions, replicationFactor));
        }
        return List.copyOf(topics);
    }
}
