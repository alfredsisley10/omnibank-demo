package com.omnibank.shared.kafka;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppMapSpanRecorderTest {

    @Test
    void rejects_non_positive_capacity() {
        assertThatThrownBy(() -> new AppMapSpanRecorder(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AppMapSpanRecorder(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void records_produce_and_consume_with_correlated_traceId() {
        AppMapSpanRecorder rec = new AppMapSpanRecorder(8);
        KafkaTraceContext root = KafkaTraceContext.newRoot();
        rec.recordProduce("topic-a", "k", root);
        rec.recordConsume("topic-a", "k", root.childSpan(), Duration.ofMillis(15));

        var spans = rec.spansForTrace(root.traceId());
        assertThat(spans).hasSize(2);
        assertThat(spans.get(0).kind()).isEqualTo(AppMapSpanRecorder.SpanKind.PRODUCE);
        assertThat(spans.get(1).kind()).isEqualTo(AppMapSpanRecorder.SpanKind.CONSUME);
        assertThat(spans.get(1).latency()).isEqualTo(Duration.ofMillis(15));
    }

    @Test
    void ring_evicts_oldest_when_capacity_exceeded() {
        AppMapSpanRecorder rec = new AppMapSpanRecorder(3);
        for (int i = 0; i < 5; i++) {
            rec.recordProduce("t", String.valueOf(i), KafkaTraceContext.newRoot());
        }
        assertThat(rec.size()).isEqualTo(3);
        var recent = rec.recent(10);
        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).key()).isEqualTo("4");
        assertThat(recent.get(2).key()).isEqualTo("2");
    }

    @Test
    void counters_track_per_topic_volume() {
        AppMapSpanRecorder rec = new AppMapSpanRecorder();
        rec.recordProduce("t1", "k", KafkaTraceContext.newRoot());
        rec.recordProduce("t1", "k", KafkaTraceContext.newRoot());
        rec.recordConsume("t1", "k", KafkaTraceContext.newRoot(), Duration.ZERO);
        assertThat(rec.counters())
                .containsEntry("produce.t1", 2L)
                .containsEntry("consume.t1", 1L);
    }

    @Test
    void recent_zero_returns_empty() {
        AppMapSpanRecorder rec = new AppMapSpanRecorder();
        rec.recordProduce("t", "k", KafkaTraceContext.newRoot());
        assertThat(rec.recent(0)).isEmpty();
    }

    @Test
    void clear_drops_all_spans_but_keeps_counters() {
        AppMapSpanRecorder rec = new AppMapSpanRecorder();
        rec.recordProduce("t", "k", KafkaTraceContext.newRoot());
        rec.clear();
        assertThat(rec.size()).isZero();
        assertThat(rec.counters()).containsKey("produce.t");
    }
}
