package com.omnibank.txstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.omnibank.shared.domain.AccountNumber;
import com.omnibank.shared.domain.CurrencyCode;
import com.omnibank.shared.domain.Money;
import com.omnibank.shared.kafka.AppMapSpanRecorder;
import com.omnibank.shared.kafka.KafkaTopics;
import com.omnibank.shared.kafka.testing.InMemoryKafkaBus;
import com.omnibank.shared.nosql.DocumentStore;
import com.omnibank.shared.nosql.inmemory.InMemoryDocumentStore;
import com.omnibank.txstream.api.StreamingTransaction;
import com.omnibank.txstream.api.StreamingTransaction.TransactionType;
import com.omnibank.txstream.api.StreamingTransactionResult;
import com.omnibank.txstream.internal.InMemoryKafkaPublishAdapter;
import com.omnibank.txstream.internal.StreamingTransactionConsumer;
import com.omnibank.txstream.internal.StreamingTransactionEntity;
import com.omnibank.txstream.internal.StreamingTransactionOrchestrator;
import com.omnibank.txstream.internal.StreamingTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamingTransactionOrchestratorTest {

    private StreamingTransactionRepository repository;
    private final List<StreamingTransactionEntity> repositoryRows = new ArrayList<>();
    private DocumentStore documents;
    private InMemoryKafkaBus bus;
    private AppMapSpanRecorder spanRecorder;
    private StreamingTransactionConsumer consumer;
    private StreamingTransactionOrchestrator orchestrator;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        repositoryRows.clear();
        repository = mock(StreamingTransactionRepository.class);
        // save() — record the row so subsequent queries can find it
        doAnswer(invocation -> {
            StreamingTransactionEntity row = invocation.getArgument(0);
            repositoryRows.removeIf(existing -> existing.transactionId().equals(row.transactionId()));
            repositoryRows.add(row);
            return row;
        }).when(repository).save(any(StreamingTransactionEntity.class));

        when(repository.findById(any(UUID.class))).thenAnswer(invocation -> {
            UUID id = invocation.getArgument(0);
            return repositoryRows.stream()
                    .filter(r -> r.transactionId().equals(id))
                    .findFirst();
        });
        when(repository.findRecentForAccount(anyString())).thenAnswer(invocation -> {
            String acct = invocation.getArgument(0);
            var matches = new ArrayList<StreamingTransactionEntity>();
            for (var r : repositoryRows) {
                if (acct.equals(r.sourceAccount()) || acct.equals(r.destinationAccount())) {
                    matches.add(r);
                }
            }
            matches.sort((a, b) -> b.initiatedAt().compareTo(a.initiatedAt()));
            return matches;
        });

        documents = new InMemoryDocumentStore();
        bus = new InMemoryKafkaBus();
        spanRecorder = new AppMapSpanRecorder();
        mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        consumer = new StreamingTransactionConsumer(documents, spanRecorder, mapper);
        bus.register(KafkaTopics.PAYMENT_EVENTS, (record, ctx) -> consumer.onRecord(record));
        orchestrator = new StreamingTransactionOrchestrator(
                repository,
                documents,
                spanRecorder,
                new InMemoryKafkaPublishAdapter(bus, mapper)
        );
    }

    @Test
    void publish_executes_all_three_legs_successfully() {
        StreamingTransaction tx = sampleTx();
        StreamingTransactionResult result = orchestrator.publish(tx);

        assertThat(result.overallSuccess()).isTrue();
        assertThat(result.sqlLeg().success()).isTrue();
        assertThat(result.mongoLeg().success()).isTrue();
        assertThat(result.kafkaLeg().success()).isTrue();

        assertThat(repositoryRows).hasSize(1);
        assertThat(documents.get(StreamingTransactionOrchestrator.MONGO_COLLECTION,
                tx.transactionId().toString())).isPresent();

        // consumer should have written the consumer-view projection too
        assertThat(documents.get(StreamingTransactionConsumer.CONSUMER_PROJECTION_COLLECTION,
                tx.transactionId().toString())).isPresent();
    }

    @Test
    void mongo_leg_failure_does_not_revert_sql_leg() {
        DocumentStore failingDocs = new FailingDocumentStore();
        StreamingTransactionOrchestrator badOrchestrator = new StreamingTransactionOrchestrator(
                repository,
                failingDocs,
                spanRecorder,
                new InMemoryKafkaPublishAdapter(bus, mapper)
        );

        StreamingTransaction tx = sampleTx();
        StreamingTransactionResult result = badOrchestrator.publish(tx);

        assertThat(result.overallSuccess()).isFalse();
        assertThat(result.sqlLeg().success()).isTrue();
        assertThat(result.mongoLeg().success()).isFalse();
        assertThat(result.warnings()).hasSize(1);
        assertThat(repositoryRows).hasSize(1);
    }

    @Test
    void recentForAccount_joins_sql_to_mongo_projection() {
        AccountNumber alice = AccountNumber.of("OB-C-ALICE001");
        AccountNumber bob   = AccountNumber.of("OB-C-BOBBY001");

        for (int i = 0; i < 3; i++) {
            orchestrator.publish(StreamingTransaction.now(
                    alice, bob,
                    Money.of(new BigDecimal("10.00"), CurrencyCode.USD),
                    TransactionType.BOOK_TRANSFER,
                    "memo " + i));
        }

        var rows = orchestrator.recentForAccount("OB-C-ALICE001", 10);
        assertThat(rows).hasSize(3);
    }

    @Test
    void seenSince_filters_to_recent_documents() {
        StreamingTransaction tx = sampleTx();
        orchestrator.publish(tx);
        var since = orchestrator.seenSince(Instant.now().minusSeconds(60), 10);
        assertThat(since).isNotEmpty();
        var future = orchestrator.seenSince(Instant.now().plusSeconds(60), 10);
        assertThat(future).isEmpty();
    }

    @Test
    void replay_returns_empty_for_unknown_id() {
        assertThat(orchestrator.replay(UUID.randomUUID())).isEmpty();
    }

    @Test
    void publish_correlates_producer_and_consumer_spans_through_traceId() {
        StreamingTransaction tx = sampleTx();
        StreamingTransactionResult result = orchestrator.publish(tx);

        var spans = spanRecorder.spansForTrace(result.traceId());
        assertThat(spans).hasSizeGreaterThanOrEqualTo(2);
        assertThat(spans.stream().map(AppMapSpanRecorder.Span::kind))
                .contains(AppMapSpanRecorder.SpanKind.PRODUCE,
                          AppMapSpanRecorder.SpanKind.CONSUME);
    }

    private static StreamingTransaction sampleTx() {
        return StreamingTransaction.now(
                AccountNumber.of("OB-C-AAAAAAAA"),
                AccountNumber.of("OB-C-BBBBBBBB"),
                Money.of(new BigDecimal("42.50"), CurrencyCode.USD),
                TransactionType.BOOK_TRANSFER,
                "test"
        );
    }

    private static class FailingDocumentStore implements DocumentStore {
        @Override public void put(String c, String id, Map<String, Object> d) {
            throw new RuntimeException("mongo down");
        }
        @Override public Optional<Map<String, Object>> get(String c, String id) { return Optional.empty(); }
        @Override public void insertOnce(String c, String id, Map<String, Object> d) {
            throw new RuntimeException("mongo down");
        }
        @Override public boolean delete(String c, String id) { return false; }
        @Override public List<Map<String, Object>> find(String c, Map<String, Object> f, int l) { return List.of(); }
        @Override public long count(String c, Map<String, Object> f) { return 0; }
        @Override public List<Map<String, Object>> since(String c, String tf, Instant s, int l) { return List.of(); }
        @Override public List<String> collections() { return List.of(); }
    }
}
