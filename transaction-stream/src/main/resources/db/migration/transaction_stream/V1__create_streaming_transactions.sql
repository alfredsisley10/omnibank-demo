CREATE TABLE txstream_transactions (
    transaction_id        UUID            NOT NULL PRIMARY KEY,
    source_account        VARCHAR(32)     NOT NULL,
    destination_account   VARCHAR(32)     NOT NULL,
    amount                DECIMAL(19, 4)  NOT NULL,
    currency              VARCHAR(3)      NOT NULL,
    type                  VARCHAR(32)     NOT NULL,
    memo                  VARCHAR(200),
    initiated_at          TIMESTAMP       NOT NULL,
    trace_id              VARCHAR(64)     NOT NULL,
    created_at            TIMESTAMP       NOT NULL,
    created_by            VARCHAR(64)     NOT NULL,
    updated_at            TIMESTAMP       NOT NULL,
    updated_by            VARCHAR(64)     NOT NULL,
    version               BIGINT          NOT NULL DEFAULT 0
);

CREATE INDEX ix_txstream_source    ON txstream_transactions(source_account);
CREATE INDEX ix_txstream_dest      ON txstream_transactions(destination_account);
CREATE INDEX ix_txstream_initiated ON txstream_transactions(initiated_at);
CREATE INDEX ix_txstream_trace     ON txstream_transactions(trace_id);
