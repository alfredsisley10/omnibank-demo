package com.omnibank.txstream.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnibank.shared.kafka.KafkaTraceContext;
import com.omnibank.shared.kafka.testing.InMemoryKafkaBus;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Default adapter used when the broker isn't enabled. Routes through
 * the in-memory bus so consumer-side AppMap traces are still produced.
 */
public class InMemoryKafkaPublishAdapter implements KafkaPublishAdapter {

    private final InMemoryKafkaBus bus;
    private final ObjectMapper mapper;
    private final AtomicLong counter = new AtomicLong();

    public InMemoryKafkaPublishAdapter(InMemoryKafkaBus bus, ObjectMapper mapper) {
        this.bus = bus;
        this.mapper = mapper;
    }

    @Override
    public void publish(String topic, String key, Object payload, KafkaTraceContext context) {
        String body;
        try {
            body = (payload instanceof CharSequence cs)
                    ? cs.toString()
                    : mapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise tx payload", e);
        }
        bus.publish(topic, key, body, context);
        counter.incrementAndGet();
    }

    @Override
    public long publishedCount() {
        return counter.get();
    }
}
