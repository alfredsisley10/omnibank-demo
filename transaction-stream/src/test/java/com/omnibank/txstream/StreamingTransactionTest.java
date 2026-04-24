package com.omnibank.txstream;

import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.txstream.api.StreamingTransaction;
import com.omnibank.txstream.api.StreamingTransaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamingTransactionTest {

    @Test
    void rejects_zero_or_negative_amount() {
        assertThatThrownBy(() -> new StreamingTransaction(
                UUID.randomUUID(),
                AccountNumber.of("OB-C-AAAAAAAA"),
                AccountNumber.of("OB-C-BBBBBBBB"),
                Money.of(BigDecimal.ZERO, CurrencyCode.USD),
                TransactionType.BOOK_TRANSFER,
                "memo",
                Instant.now()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_same_source_and_destination_unless_memo_adjustment() {
        AccountNumber a = AccountNumber.of("OB-C-AAAAAAAA");
        assertThatThrownBy(() -> new StreamingTransaction(
                UUID.randomUUID(), a, a,
                Money.of(BigDecimal.ONE, CurrencyCode.USD),
                TransactionType.BOOK_TRANSFER, "", Instant.now()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allows_same_account_for_memo_adjustments() {
        AccountNumber a = AccountNumber.of("OB-C-AAAAAAAA");
        StreamingTransaction tx = new StreamingTransaction(
                UUID.randomUUID(), a, a,
                Money.of(BigDecimal.ONE, CurrencyCode.USD),
                TransactionType.MEMO_ADJUSTMENT, "", Instant.now());
        assertThat(tx.sourceAccount()).isEqualTo(a);
    }

    @Test
    void now_factory_yields_random_id_and_uses_supplied_inputs() {
        AccountNumber a = AccountNumber.of("OB-C-AAAAAAAA");
        AccountNumber b = AccountNumber.of("OB-C-BBBBBBBB");
        StreamingTransaction tx = StreamingTransaction.now(a, b,
                Money.of(BigDecimal.TEN, CurrencyCode.USD),
                TransactionType.BOOK_TRANSFER,
                "test");
        assertThat(tx.sourceAccount()).isEqualTo(a);
        assertThat(tx.destinationAccount()).isEqualTo(b);
        assertThat(tx.transactionId()).isNotNull();
    }
}
