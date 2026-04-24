package com.omnibank.txstream;

import com.omnibank.txstream.api.StreamingTransactionResult;
import com.omnibank.txstream.api.StreamingTransactionResult.LegOutcome;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingTransactionResultTest {

    @Test
    void slowestLegDuration_picks_max() {
        StreamingTransactionResult r = new StreamingTransactionResult(
                UUID.randomUUID(),
                true,
                LegOutcome.success(Duration.ofMillis(20), "sql"),
                LegOutcome.success(Duration.ofMillis(50), "mongo"),
                LegOutcome.success(Duration.ofMillis(35), "kafka"),
                Duration.ofMillis(120),
                "trace-1",
                List.of()
        );
        assertThat(r.slowestLegDuration()).isEqualTo(Duration.ofMillis(50));
        assertThat(r.hasWarnings()).isFalse();
    }

    @Test
    void warnings_are_immutable_copies() {
        java.util.List<String> mutable = new java.util.ArrayList<>(List.of("first"));
        StreamingTransactionResult r = new StreamingTransactionResult(
                UUID.randomUUID(),
                false,
                LegOutcome.success(Duration.ZERO, "ok"),
                LegOutcome.failure(Duration.ZERO, "x"),
                LegOutcome.success(Duration.ZERO, "ok"),
                Duration.ZERO,
                "trace",
                mutable
        );
        mutable.add("second");
        assertThat(r.warnings()).containsExactly("first");
        assertThat(r.hasWarnings()).isTrue();
    }
}
