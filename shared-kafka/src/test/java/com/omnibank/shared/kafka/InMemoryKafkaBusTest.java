package com.omnibank.shared.kafka;

import com.omnibank.shared.kafka.testing.InMemoryKafkaBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryKafkaBusTest {

    @Test
    void publish_dispatches_to_registered_handlers() {
        InMemoryKafkaBus bus = new InMemoryKafkaBus();
        var seen = new ArrayList<String>();
        bus.register("t1", (record, ctx) -> seen.add(record.value()));
        bus.register("t1", (record, ctx) -> seen.add("dup:" + record.value()));

        bus.publish("t1", "key", "{\"x\":1}", KafkaTraceContext.newRoot());
        assertThat(seen).containsExactly("{\"x\":1}", "dup:{\"x\":1}");
    }

    @Test
    void publish_assigns_monotonic_offsets_per_topic() {
        InMemoryKafkaBus bus = new InMemoryKafkaBus();
        List<Long> offsets = new ArrayList<>();
        bus.register("t", (rec, ctx) -> offsets.add(rec.offset()));
        for (int i = 0; i < 4; i++) {
            bus.publish("t", "k", "v" + i, KafkaTraceContext.newRoot());
        }
        assertThat(offsets).containsExactly(0L, 1L, 2L, 3L);
    }

    @Test
    void trace_context_round_trips_through_headers() {
        InMemoryKafkaBus bus = new InMemoryKafkaBus();
        var captured = new ArrayList<KafkaTraceContext>();
        bus.register("t", (rec, ctx) -> {
            // verify reconstruction via headers, not just the supplied ctx
            captured.add(KafkaTraceContext.fromHeaders(java.util.Map.of(
                    KafkaTraceContext.HEADER_TRACE_ID, rec.headers().lastHeader(KafkaTraceContext.HEADER_TRACE_ID).value(),
                    KafkaTraceContext.HEADER_SPAN_ID,  rec.headers().lastHeader(KafkaTraceContext.HEADER_SPAN_ID).value()
            )));
        });
        KafkaTraceContext root = KafkaTraceContext.newRoot();
        bus.publish("t", "k", "v", root);
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).traceId()).isEqualTo(root.traceId());
    }

    @Test
    void backlog_returns_published_messages() {
        InMemoryKafkaBus bus = new InMemoryKafkaBus();
        bus.publish("topic", "k", "v", KafkaTraceContext.newRoot());
        bus.publish("topic", "k2", "v2", KafkaTraceContext.newRoot());
        var backlog = bus.backlog("topic");
        assertThat(backlog).hasSize(2);
        assertThat(backlog.get(1).key()).isEqualTo("k2");
    }
}
