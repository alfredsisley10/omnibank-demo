package com.omnibank.txstream.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of {@link StreamingTransactionService#publish}.
 *
 * <p>Always reports timings for each leg so AppMap traces and the
 * recording UI can highlight which store dominates the latency. Per-leg
 * outcomes are surfaced individually because we want failures in the
 * Mongo projection to be visible without rolling back the SQL leg —
 * a recurring theme in the existing payments hub.</p>
 */
public record StreamingTransactionResult(
        UUID transactionId,
        boolean overallSuccess,
        LegOutcome sqlLeg,
        LegOutcome mongoLeg,
        LegOutcome kafkaLeg,
        Duration totalDuration,
        String traceId,
        List<String> warnings
) {

    public StreamingTransactionResult {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(sqlLeg, "sqlLeg");
        Objects.requireNonNull(mongoLeg, "mongoLeg");
        Objects.requireNonNull(kafkaLeg, "kafkaLeg");
        Objects.requireNonNull(totalDuration, "totalDuration");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(warnings, "warnings");
        warnings = List.copyOf(warnings);
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    public Duration slowestLegDuration() {
        Duration s = sqlLeg.duration();
        Duration m = mongoLeg.duration();
        Duration k = kafkaLeg.duration();
        return s.compareTo(m) >= 0
                ? (s.compareTo(k) >= 0 ? s : k)
                : (m.compareTo(k) >= 0 ? m : k);
    }

    public record LegOutcome(boolean success, Duration duration, String detail) {

        public LegOutcome {
            Objects.requireNonNull(duration, "duration");
            Objects.requireNonNull(detail, "detail");
        }

        public static LegOutcome success(Duration d, String detail) {
            return new LegOutcome(true, d, detail);
        }

        public static LegOutcome failure(Duration d, String detail) {
            return new LegOutcome(false, d, detail);
        }
    }
}
