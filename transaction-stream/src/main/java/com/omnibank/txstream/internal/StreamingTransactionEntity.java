package com.omnibank.txstream.internal;

import com.omnibank.shared.persistence.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * SQL-side row for a streaming transaction. Owns the canonical state;
 * the Mongo projection is derived from this and may lag.
 */
@Entity
@Table(name = "txstream_transactions",
        indexes = {
                @Index(name = "ix_txstream_source", columnList = "source_account"),
                @Index(name = "ix_txstream_dest",   columnList = "destination_account"),
                @Index(name = "ix_txstream_initiated", columnList = "initiated_at")
        })
public class StreamingTransactionEntity extends AuditableEntity {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "source_account", nullable = false, length = 32)
    private String sourceAccount;

    @Column(name = "destination_account", nullable = false, length = 32)
    private String destinationAccount;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private com.omnibank.txstream.api.StreamingTransaction.TransactionType type;

    @Column(name = "memo", length = 200)
    private String memo;

    @Column(name = "initiated_at", nullable = false)
    private Instant initiatedAt;

    @Column(name = "trace_id", nullable = false, length = 64)
    private String traceId;

    protected StreamingTransactionEntity() {
        // for JPA
    }

    public StreamingTransactionEntity(UUID transactionId,
                                      String sourceAccount,
                                      String destinationAccount,
                                      BigDecimal amount,
                                      String currency,
                                      com.omnibank.txstream.api.StreamingTransaction.TransactionType type,
                                      String memo,
                                      Instant initiatedAt,
                                      String traceId) {
        this.transactionId = transactionId;
        this.sourceAccount = sourceAccount;
        this.destinationAccount = destinationAccount;
        this.amount = amount;
        this.currency = currency;
        this.type = type;
        this.memo = memo;
        this.initiatedAt = initiatedAt;
        this.traceId = traceId;
    }

    public UUID transactionId() { return transactionId; }
    public String sourceAccount() { return sourceAccount; }
    public String destinationAccount() { return destinationAccount; }
    public BigDecimal amount() { return amount; }
    public String currency() { return currency; }
    public com.omnibank.txstream.api.StreamingTransaction.TransactionType type() { return type; }
    public String memo() { return memo; }
    public Instant initiatedAt() { return initiatedAt; }
    public String traceId() { return traceId; }
}
