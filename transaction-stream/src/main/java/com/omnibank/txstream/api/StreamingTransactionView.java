package com.omnibank.txstream.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read-side projection of a streaming transaction. Built from the Mongo
 * document and joined back to its SQL counterpart. Used by the
 * recording UI's "live transactions" panel and by the integration tests
 * that assert cross-database fan-out.
 */
public record StreamingTransactionView(
        UUID transactionId,
        String sourceAccount,
        String destinationAccount,
        String type,
        String currency,
        java.math.BigDecimal amount,
        Instant initiatedAt,
        String traceId,
        Map<String, Object> raw
) {}
