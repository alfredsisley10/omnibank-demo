package com.omnibank.shared.kafka;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaTraceContextTest {

    @Test
    void newRoot_has_no_parent_and_no_recording() {
        KafkaTraceContext c = KafkaTraceContext.newRoot();
        assertThat(c.parentSpanId()).isEmpty();
        assertThat(c.recordingId()).isEmpty();
        assertThat(c.traceId()).isNotBlank();
        assertThat(c.spanId()).isNotBlank();
    }

    @Test
    void childSpan_keeps_traceId_changes_spanId_links_parent() {
        KafkaTraceContext parent = KafkaTraceContext.newRoot();
        KafkaTraceContext child = parent.childSpan();
        assertThat(child.traceId()).isEqualTo(parent.traceId());
        assertThat(child.spanId()).isNotEqualTo(parent.spanId());
        assertThat(child.parentSpanId()).contains(parent.spanId());
    }

    @Test
    void headers_round_trip() {
        KafkaTraceContext original = KafkaTraceContext.newRoot()
                .withRecording("rec-001")
                .withBaggage("tenant", "acme")
                .withBaggage("region", "us-east-1");

        Map<String, byte[]> headers = original.toHeaders();
        KafkaTraceContext recovered = KafkaTraceContext.fromHeaders(headers);

        assertThat(recovered.traceId()).isEqualTo(original.traceId());
        assertThat(recovered.spanId()).isEqualTo(original.spanId());
        assertThat(recovered.recordingId()).contains("rec-001");
        assertThat(recovered.baggage())
                .containsEntry("tenant", "acme")
                .containsEntry("region", "us-east-1");
    }

    @Test
    void withBaggage_null_value_removes_key() {
        KafkaTraceContext c = KafkaTraceContext.newRoot()
                .withBaggage("k", "v")
                .withBaggage("k", null);
        assertThat(c.baggage()).doesNotContainKey("k");
    }

    @Test
    void fromHeaders_empty_returns_fresh_root() {
        KafkaTraceContext c = KafkaTraceContext.fromHeaders(Map.of());
        assertThat(c.parentSpanId()).isEmpty();
        assertThat(c.traceId()).isNotBlank();
    }

    @Test
    void fromHeaders_missing_traceId_returns_fresh_root() {
        KafkaTraceContext c = KafkaTraceContext.fromHeaders(
                Map.of("x-other", "x".getBytes()));
        assertThat(c.parentSpanId()).isEmpty();
    }

    @Test
    void blank_traceId_is_rejected() {
        assertThatThrownBy(() -> new KafkaTraceContext("",
                "span", java.util.Optional.empty(), java.util.Optional.empty(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void baggage_special_characters_round_trip() {
        KafkaTraceContext c = KafkaTraceContext.newRoot()
                .withBaggage("a,b", "c=d");
        Map<String, byte[]> headers = c.toHeaders();
        KafkaTraceContext recovered = KafkaTraceContext.fromHeaders(headers);
        // commas and equals signs are escaped to underscores
        assertThat(recovered.baggage()).containsEntry("a_b", "c_d");
    }
}
