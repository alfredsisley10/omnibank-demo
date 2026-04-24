package com.omnibank.txstream.internal;

import com.omnibank.shared.kafka.KafkaTraceContext;
import com.omnibank.shared.kafka.TracedKafkaPublisher;

import java.util.concurrent.atomic.AtomicLong;

/**
 * KafkaPublishAdapter that delegates to the shared TracedKafkaPublisher,
 * which talks to the real broker.
 */
public class TracedKafkaPublishAdapter implements KafkaPublishAdapter {

    private final TracedKafkaPublisher delegate;
    private final long timeoutMs;
    private final AtomicLong counter = new AtomicLong();

    public TracedKafkaPublishAdapter(TracedKafkaPublisher delegate, long timeoutMs) {
        this.delegate = delegate;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void publish(String topic, String key, Object payload, KafkaTraceContext context) {
        delegate.publishSync(topic, key, payload, context, timeoutMs);
        counter.incrementAndGet();
    }

    @Override
    public long publishedCount() {
        return counter.get();
    }
}
