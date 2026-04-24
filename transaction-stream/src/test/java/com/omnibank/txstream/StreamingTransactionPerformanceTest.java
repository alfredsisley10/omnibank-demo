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
import com.omnibank.txstream.internal.StreamingTransactionOrchestrator;
import com.omnibank.txstream.internal.StreamingTransactionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Soft performance budget for the streaming-transaction orchestrator.
 *
 * <p>The numbers here are intentionally generous (the test must pass on
 * a warmly-loaded laptop and a slow CI runner alike) — the point is to
 * catch order-of-magnitude regressions, not micro-benchmark.</p>
 *
 * <p>Two scenarios are exercised:
 * <ol>
 *   <li><b>Sequential throughput</b> — single-threaded publish loop;
 *       used to detect accidental N+1 queries or pathological string
 *       building.</li>
 *   <li><b>Concurrent throughput</b> — fan a fixed publish budget across
 *       a small thread pool; sanity-check thread safety.</li>
 * </ol>
 */
class StreamingTransactionPerformanceTest {

    private static final int SEQUENTIAL_TX = 500;
    private static final int CONCURRENT_TX = 1000;
    private static final int CONCURRENT_THREADS = 4;
    private static final Duration SEQUENTIAL_BUDGET = Duration.ofSeconds(10);
    private static final Duration CONCURRENT_BUDGET = Duration.ofSeconds(15);

    @Test
    void sequential_publish_within_budget() {
        var harness = new Harness();

        Instant start = Instant.now();
        for (int i = 0; i < SEQUENTIAL_TX; i++) {
            harness.publish(i);
        }
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(elapsed)
                .as("sequential publish of %d transactions should finish within %s",
                        SEQUENTIAL_TX, SEQUENTIAL_BUDGET)
                .isLessThan(SEQUENTIAL_BUDGET);
        assertThat(harness.savedRows).hasSize(SEQUENTIAL_TX);
        assertThat(harness.consumerProjections())
                .as("consumer should observe one document per published tx")
                .isEqualTo(SEQUENTIAL_TX);
    }

    @Test
    void concurrent_publish_keeps_traceIds_distinct() throws Exception {
        var harness = new Harness();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        try {
            List<Future<String>> futures = new ArrayList<>();
            int perThread = CONCURRENT_TX / CONCURRENT_THREADS;
            Instant start = Instant.now();
            for (int t = 0; t < CONCURRENT_THREADS; t++) {
                final int threadId = t;
                futures.add(pool.submit(() -> {
                    StringBuilder seen = new StringBuilder();
                    for (int i = 0; i < perThread; i++) {
                        StreamingTransactionResult r = harness.publish(threadId * perThread + i);
                        seen.append(r.traceId()).append('\n');
                    }
                    return seen.toString();
                }));
            }
            var traceIds = new java.util.HashSet<String>();
            for (var f : futures) {
                String[] lines = f.get(60, TimeUnit.SECONDS).split("\n");
                for (String line : lines) {
                    if (!line.isEmpty()) traceIds.add(line);
                }
            }
            Duration elapsed = Duration.between(start, Instant.now());

            assertThat(elapsed)
                    .as("concurrent publish of %d transactions across %d threads " +
                                    "should finish within %s",
                            CONCURRENT_TX, CONCURRENT_THREADS, CONCURRENT_BUDGET)
                    .isLessThan(CONCURRENT_BUDGET);
            assertThat(traceIds)
                    .as("every publish should mint a fresh trace id")
                    .hasSize(CONCURRENT_TX);
            assertThat(harness.savedRows).hasSize(CONCURRENT_TX);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Bundle the test plumbing so each test can set up identically. */
    private static class Harness {

        final List<com.omnibank.txstream.internal.StreamingTransactionEntity> savedRows =
                java.util.Collections.synchronizedList(new ArrayList<>());
        final DocumentStore documents = new InMemoryDocumentStore();
        final InMemoryKafkaBus bus = new InMemoryKafkaBus();
        final AppMapSpanRecorder spanRecorder = new AppMapSpanRecorder(8192);
        final ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        final StreamingTransactionConsumer consumer =
                new StreamingTransactionConsumer(documents, spanRecorder, mapper);
        final StreamingTransactionOrchestrator orchestrator;
        final StreamingTransactionRepository repository;

        Harness() {
            repository = mock(StreamingTransactionRepository.class);
            doAnswer(invocation -> {
                var row = (com.omnibank.txstream.internal.StreamingTransactionEntity)
                        invocation.getArgument(0);
                savedRows.add(row);
                return row;
            }).when(repository).save(any(com.omnibank.txstream.internal.StreamingTransactionEntity.class));

            bus.register(KafkaTopics.PAYMENT_EVENTS,
                    (record, ctx) -> consumer.onRecord(record));
            orchestrator = new StreamingTransactionOrchestrator(
                    repository, documents, spanRecorder,
                    new InMemoryKafkaPublishAdapter(bus, mapper));
        }

        StreamingTransactionResult publish(int seq) {
            String aSuffix = String.format("%08d", seq).substring(0, 8).toUpperCase();
            String bSuffix = String.format("%08d", seq + 1000000).substring(0, 8).toUpperCase();
            // Account regex requires [A-Z0-9]{8}; pad with letters when needed.
            AccountNumber from = AccountNumber.of("OB-C-" + sanitize(aSuffix));
            AccountNumber to   = AccountNumber.of("OB-C-" + sanitize(bSuffix));
            return orchestrator.publish(new StreamingTransaction(
                    UUID.randomUUID(),
                    from, to,
                    Money.of(BigDecimal.valueOf(seq + 1), CurrencyCode.USD),
                    TransactionType.BOOK_TRANSFER,
                    "perf seq=" + seq,
                    Instant.now()
            ));
        }

        long consumerProjections() {
            return documents.count(StreamingTransactionConsumer.CONSUMER_PROJECTION_COLLECTION,
                    java.util.Map.of());
        }

        private static String sanitize(String raw) {
            // ensure all 8 chars are A-Z0-9 — substitute any leading 0
            // for an A so the regex matches.
            char[] chars = raw.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                if (chars[i] < 'A') {
                    chars[i] = (char) ('A' + (chars[i] - '0'));
                }
            }
            return new String(chars);
        }
    }
}
