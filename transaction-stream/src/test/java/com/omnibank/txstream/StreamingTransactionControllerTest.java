package com.omnibank.txstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.omnibank.appmaprec.api.RecordingService;
import com.omnibank.appmaprec.internal.AppMapAgentBridge;
import com.omnibank.appmaprec.internal.InMemoryRecordingService;
import com.omnibank.appmaprec.internal.RecordingArchive;
import com.omnibank.shared.kafka.AppMapSpanRecorder;
import com.omnibank.shared.kafka.KafkaTopics;
import com.omnibank.shared.kafka.testing.InMemoryKafkaBus;
import com.omnibank.shared.nosql.inmemory.InMemoryDocumentStore;
import com.omnibank.txstream.internal.InMemoryKafkaPublishAdapter;
import com.omnibank.txstream.internal.StreamingTransactionConsumer;
import com.omnibank.txstream.internal.StreamingTransactionEntity;
import com.omnibank.txstream.internal.StreamingTransactionOrchestrator;
import com.omnibank.txstream.internal.StreamingTransactionRepository;
import com.omnibank.txstream.web.StreamingTransactionController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StreamingTransactionControllerTest {

    private MockMvc mvc;
    private RecordingService recordings;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        var rows = new ArrayList<StreamingTransactionEntity>();
        StreamingTransactionRepository repo = mock(StreamingTransactionRepository.class);
        doAnswer(inv -> {
            rows.add(inv.getArgument(0));
            return inv.getArgument(0);
        }).when(repo).save(any(StreamingTransactionEntity.class));
        when(repo.findRecentForAccount(anyString())).thenAnswer(inv -> {
            String acct = inv.getArgument(0);
            return rows.stream()
                    .filter(r -> acct.equals(r.sourceAccount()) || acct.equals(r.destinationAccount()))
                    .toList();
        });

        var documents = new InMemoryDocumentStore();
        var bus = new InMemoryKafkaBus();
        var spanRecorder = new AppMapSpanRecorder();
        var consumer = new StreamingTransactionConsumer(documents, spanRecorder, mapper);
        bus.register(KafkaTopics.PAYMENT_EVENTS, (record, ctx) -> consumer.onRecord(record));

        var orchestrator = new StreamingTransactionOrchestrator(
                repo, documents, spanRecorder,
                new InMemoryKafkaPublishAdapter(bus, mapper));

        recordings = new InMemoryRecordingService(
                new AppMapAgentBridge(true),
                new RecordingArchive(tmp),
                Clock.systemUTC());

        mvc = MockMvcBuilders
                .standaloneSetup(new StreamingTransactionController(orchestrator, spanRecorder, recordings))
                .build();
    }

    @Test
    void publish_returns_overall_success_with_all_legs_green() throws Exception {
        Map<String, Object> body = Map.of(
                "sourceAccount", "OB-C-AAAAAAAA",
                "destinationAccount", "OB-C-BBBBBBBB",
                "amount", 25,
                "currency", "USD",
                "type", "BOOK_TRANSFER",
                "memo", "tc"
        );
        mvc.perform(post("/api/v1/txstream/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallSuccess").value(true))
                .andExpect(jsonPath("$.sqlLeg.success").value(true))
                .andExpect(jsonPath("$.mongoLeg.success").value(true))
                .andExpect(jsonPath("$.kafkaLeg.success").value(true));
    }

    @Test
    void publish_with_recordingId_appends_action_to_recording() throws Exception {
        var rec = recordings.start("manual", "for test");
        Map<String, Object> body = Map.of(
                "recordingId", rec.id().value(),
                "sourceAccount", "OB-C-CCCCCCCC",
                "destinationAccount", "OB-C-DDDDDDDD",
                "amount", 50,
                "currency", "USD",
                "type", "ACH_DEBIT"
        );
        mvc.perform(post("/api/v1/txstream/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());
        var current = recordings.get(rec.id()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(current.actions()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(current.actions().get(0).kind())
                .isEqualTo("txstream.publish");
    }

    @Test
    void spans_endpoint_exposes_counters() throws Exception {
        Map<String, Object> body = Map.of(
                "sourceAccount", "OB-C-EEEEEEEE",
                "destinationAccount", "OB-C-FFFFFFFF",
                "amount", 12,
                "currency", "USD"
        );
        mvc.perform(post("/api/v1/txstream/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/txstream/spans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counters['produce." + KafkaTopics.PAYMENT_EVENTS + "']")
                        .value(1));
    }
}
