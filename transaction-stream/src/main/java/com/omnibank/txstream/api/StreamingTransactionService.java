package com.omnibank.txstream.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates the SQL + Mongo + Kafka legs of a streaming transaction.
 *
 * <p>Implementations must guarantee:
 * <ul>
 *   <li>The SQL ledger leg either commits fully or not at all
 *       (transactional system of record);</li>
 *   <li>The Mongo projection is best-effort but always attempted;</li>
 *   <li>The Kafka emit is best-effort, async, and stamped with a
 *       trace context so AppMap can join producer + consumer spans.</li>
 * </ul>
 */
public interface StreamingTransactionService {

    StreamingTransactionResult publish(StreamingTransaction tx);

    /** Replay the stored Mongo projection for a transaction id, if any. */
    Optional<StreamingTransactionView> replay(UUID transactionId);

    /** Find recent transactions touching the supplied account, newest first. */
    List<StreamingTransactionView> recentForAccount(String accountNumber, int limit);

    /** Find recent transactions seen since {@code since}. */
    List<StreamingTransactionView> seenSince(Instant since, int limit);
}
