package com.omnibank.shared.nosql;

import com.omnibank.shared.nosql.inmemory.InMemoryDocumentStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryDocumentStoreTest {

    private DocumentStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryDocumentStore();
    }

    @Test
    void put_then_get_round_trips() {
        Map<String, Object> doc = Map.of("name", "alice", "balance", 1000);
        store.put("accounts", "acct-1", doc);
        var loaded = store.get("accounts", "acct-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get())
                .containsEntry("name", "alice")
                .containsEntry("balance", 1000);
    }

    @Test
    void put_overwrites_existing_id() {
        store.put("accounts", "acct-1", Map.of("v", 1));
        store.put("accounts", "acct-1", Map.of("v", 2));
        assertThat(store.get("accounts", "acct-1")).contains(Map.of("v", 2));
    }

    @Test
    void insertOnce_rejects_duplicate_id() {
        store.insertOnce("accounts", "acct-1", Map.of("v", 1));
        assertThatThrownBy(() -> store.insertOnce("accounts", "acct-1", Map.of("v", 2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("acct-1");
    }

    @Test
    void delete_removes_document() {
        store.put("accounts", "a", Map.of("v", 1));
        assertThat(store.delete("accounts", "a")).isTrue();
        assertThat(store.delete("accounts", "a")).isFalse();
        assertThat(store.get("accounts", "a")).isEmpty();
    }

    @Test
    void find_filter_matches_subset() {
        store.put("c", "1", Map.of("color", "red", "size", "L"));
        store.put("c", "2", Map.of("color", "red", "size", "M"));
        store.put("c", "3", Map.of("color", "blue", "size", "L"));

        var red = store.find("c", Map.of("color", "red"), 0);
        assertThat(red).hasSize(2);

        var redL = store.find("c", new LinkedHashMap<>(Map.of("color", "red", "size", "L")), 0);
        assertThat(redL).hasSize(1);

        assertThat(store.count("c", Map.of("color", "red"))).isEqualTo(2);
    }

    @Test
    void find_with_limit_truncates() {
        for (int i = 0; i < 10; i++) store.put("c", String.valueOf(i), Map.of("k", "v"));
        var slice = store.find("c", Map.of(), 3);
        assertThat(slice).hasSize(3);
    }

    @Test
    void since_returns_documents_at_or_after_threshold() {
        Instant t0 = Instant.parse("2026-04-24T10:00:00Z");
        Instant t1 = Instant.parse("2026-04-24T11:00:00Z");
        Instant t2 = Instant.parse("2026-04-24T12:00:00Z");

        store.put("events", "e0", Map.of("ts", t0));
        store.put("events", "e1", Map.of("ts", t1));
        store.put("events", "e2", Map.of("ts", t2));

        var fromT1 = store.since("events", "ts", t1, 0);
        assertThat(fromT1).hasSize(2);
        assertThat(fromT1.get(0).get("ts")).isEqualTo(t1);
        assertThat(fromT1.get(1).get("ts")).isEqualTo(t2);
    }

    @Test
    void since_handles_string_timestamps() {
        store.put("events", "e0", Map.of("ts", "2026-04-24T10:00:00Z"));
        store.put("events", "e1", Map.of("ts", "2026-04-24T12:00:00Z"));
        var fromNoon = store.since("events", "ts",
                Instant.parse("2026-04-24T12:00:00Z"), 0);
        assertThat(fromNoon).hasSize(1);
    }

    @Test
    void put_does_not_share_mutable_state_with_caller() {
        var doc = new LinkedHashMap<String, Object>();
        doc.put("v", 1);
        store.put("c", "id", doc);
        doc.put("v", 99);
        assertThat(store.get("c", "id")).contains(Map.of("v", 1));
    }

    @Test
    void collections_lists_known_collections() {
        store.put("a", "1", Map.of("x", "y"));
        store.put("b", "2", Map.of("x", "y"));
        assertThat(store.collections()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void empty_collection_returns_empty_results() {
        assertThat(store.find("missing", Map.of(), 10)).isEmpty();
        assertThat(store.count("missing", Map.of())).isZero();
        assertThat(store.since("missing", "ts", Instant.now(), 10)).isEmpty();
        assertThat(store.get("missing", "id")).isEmpty();
        assertThat(store.delete("missing", "id")).isFalse();
    }

    @Test
    void deep_copy_isolates_nested_lists_and_maps() {
        var nested = new LinkedHashMap<String, Object>();
        nested.put("inner", "val");
        var doc = new LinkedHashMap<String, Object>();
        doc.put("nested", nested);
        doc.put("list", List.of(1, 2, 3));
        store.put("c", "id", doc);

        nested.put("inner", "mutated");
        var loaded = store.get("c", "id").orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> loadedNested = (Map<String, Object>) loaded.get("nested");
        assertThat(loadedNested).containsEntry("inner", "val");
    }
}
