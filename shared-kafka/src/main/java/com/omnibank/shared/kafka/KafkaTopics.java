package com.omnibank.shared.kafka;

import java.util.List;

/**
 * Single source of truth for Kafka topic names used across Omnibank.
 *
 * <p>Topics are split into three families:
 * <ul>
 *   <li><b>Events</b> — append-only fact streams; consumers materialise
 *       projections.</li>
 *   <li><b>Commands</b> — durable instructions targeting one consumer
 *       group; processed exactly once via the outbox pattern.</li>
 *   <li><b>Audit</b> — wide, low-cardinality firehose used by AppMap
 *       trace correlators and ops dashboards.</li>
 * </ul>
 */
public final class KafkaTopics {

    private KafkaTopics() {}

    public static final String PAYMENT_EVENTS         = "omnibank.payment.events";
    public static final String LEDGER_EVENTS          = "omnibank.ledger.events";
    public static final String ACCOUNT_EVENTS         = "omnibank.account.events";
    public static final String FRAUD_SIGNALS          = "omnibank.fraud.signals";
    public static final String COMPLIANCE_ALERTS      = "omnibank.compliance.alerts";

    public static final String COMMAND_PAYMENT_SUBMIT = "omnibank.payment.command.submit";
    public static final String COMMAND_LEDGER_POST    = "omnibank.ledger.command.post";

    public static final String AUDIT_TRAIL            = "omnibank.audit.trail";
    public static final String APPMAP_SPANS           = "omnibank.appmap.spans";

    public static final List<String> ALL_EVENT_TOPICS = List.of(
            PAYMENT_EVENTS, LEDGER_EVENTS, ACCOUNT_EVENTS,
            FRAUD_SIGNALS, COMPLIANCE_ALERTS
    );

    public static final List<String> ALL_COMMAND_TOPICS = List.of(
            COMMAND_PAYMENT_SUBMIT, COMMAND_LEDGER_POST
    );

    public static final List<String> ALL_AUDIT_TOPICS = List.of(
            AUDIT_TRAIL, APPMAP_SPANS
    );

    public static List<String> all() {
        var combined = new java.util.ArrayList<String>();
        combined.addAll(ALL_EVENT_TOPICS);
        combined.addAll(ALL_COMMAND_TOPICS);
        combined.addAll(ALL_AUDIT_TOPICS);
        return List.copyOf(combined);
    }
}
