package com.omnibank.shared.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests against the publisher's pure logic that doesn't require a real
 * KafkaTemplate. The full producer→consumer round-trip is covered by
 * the Testcontainers-based integration suite.
 */
class TracedKafkaPublisherTest {

    @SuppressWarnings("unchecked")
    @Test
    void applyHeaders_writes_all_expected_keys() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        var publisher = new TracedKafkaPublisher(template, new ObjectMapper(), false);
        KafkaTraceContext ctx = KafkaTraceContext.newRoot()
                .withRecording("rec-headers")
                .withBaggage("env", "ci");

        var headers = new RecordHeaders();
        publisher.applyHeaders(headers, ctx);

        assertThat(headers.lastHeader(KafkaTraceContext.HEADER_TRACE_ID)).isNotNull();
        assertThat(headers.lastHeader(KafkaTraceContext.HEADER_SPAN_ID)).isNotNull();
        assertThat(headers.lastHeader(KafkaTraceContext.HEADER_RECORDING)).isNotNull();
        assertThat(headers.lastHeader(KafkaTraceContext.HEADER_BAGGAGE)).isNotNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void auditMirror_flag_round_trips() {
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        var p1 = new TracedKafkaPublisher(template, new ObjectMapper(), true);
        var p2 = new TracedKafkaPublisher(template, new ObjectMapper(), false);
        assertThat(p1.auditMirrorEnabled()).isTrue();
        assertThat(p2.auditMirrorEnabled()).isFalse();
    }
}
