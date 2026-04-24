package com.omnibank.txstream.api;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.Money;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable input to a "streaming" cross-store transaction.
 *
 * <p>A streaming transaction is a single business event that is observed
 * three places at once:
 * <ol>
 *   <li>persisted to the SQL ledger (system of record);</li>
 *   <li>projected into a Mongo collection for fast lookup;</li>
 *   <li>emitted to Kafka so downstream services can react.</li>
 * </ol>
 *
 * <p>This record is the canonical input. {@link StreamingTransactionResult}
 * is the matching output.</p>
 */
public record StreamingTransaction(
        UUID transactionId,
        AccountNumber sourceAccount,
        AccountNumber destinationAccount,
        Money amount,
        TransactionType type,
        String memo,
        Instant initiatedAt
) {

    public StreamingTransaction {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(sourceAccount, "sourceAccount");
        Objects.requireNonNull(destinationAccount, "destinationAccount");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(initiatedAt, "initiatedAt");
        if (amount.isZero() || amount.isNegative()) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        if (sourceAccount.equals(destinationAccount) && type != TransactionType.MEMO_ADJUSTMENT) {
            throw new IllegalArgumentException(
                    "source and destination must differ for type " + type);
        }
    }

    public static StreamingTransaction now(AccountNumber from, AccountNumber to,
                                           Money amount, TransactionType type, String memo) {
        return new StreamingTransaction(
                UUID.randomUUID(), from, to, amount, type, memo, Instant.now());
    }

    public enum TransactionType {
        BOOK_TRANSFER,
        ACH_DEBIT,
        ACH_CREDIT,
        WIRE_OUTBOUND,
        WIRE_INBOUND,
        FEE_ASSESSMENT,
        INTEREST_ACCRUAL,
        MEMO_ADJUSTMENT
    }
}
