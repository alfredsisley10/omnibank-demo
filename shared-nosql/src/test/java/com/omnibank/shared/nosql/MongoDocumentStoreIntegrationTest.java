package com.omnibank.shared.nosql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip tests against a real MongoDB container. Skipped by default
 * (requires Docker) — run with {@code ./gradlew :shared-nosql:test
 * -PincludeDocker=true} on a machine that can pull MongoDB.
 */
@Tag("docker")
@Testcontainers
class MongoDocumentStoreIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(
            DockerImageName.parse("mongo:7.0"));

    private MongoTemplate template;
    private DocumentStore store;
    private String collection;

    @BeforeEach
    void setUp() {
        SimpleMongoClientDatabaseFactory factory =
                new SimpleMongoClientDatabaseFactory(MONGO.getReplicaSetUrl("omnibank-test"));
        template = new MongoTemplate(factory);
        store = new MongoDocumentStore(template);
        collection = "test-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        if (template != null) template.dropCollection(collection);
    }

    @Test
    void put_then_get_round_trips() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("name", "alice");
        doc.put("balance", 1000);
        store.put(collection, "acct-1", doc);

        var loaded = store.get(collection, "acct-1");
        assertThat(loaded).isPresent();
        assertThat(loaded.get())
                .containsEntry("_id", "acct-1")
                .containsEntry("name", "alice")
                .containsEntry("balance", 1000);
    }

    @Test
    void insertOnce_rejects_duplicate() {
        store.insertOnce(collection, "id", Map.of("v", 1));
        assertThatThrownBy(() ->
                store.insertOnce(collection, "id", Map.of("v", 2)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void find_with_filter_filters() {
        store.put(collection, "1", Map.of("color", "red"));
        store.put(collection, "2", Map.of("color", "red"));
        store.put(collection, "3", Map.of("color", "blue"));

        assertThat(store.find(collection, Map.of("color", "red"), 0)).hasSize(2);
        assertThat(store.count(collection, Map.of("color", "blue"))).isEqualTo(1);
    }

    @Test
    void since_returns_documents_after_threshold() {
        Instant t0 = Instant.parse("2026-04-24T10:00:00Z");
        Instant t1 = Instant.parse("2026-04-24T11:00:00Z");
        store.put(collection, "e0", Map.of("ts", t0));
        store.put(collection, "e1", Map.of("ts", t1));

        var fromT1 = store.since(collection, "ts", t1, 0);
        assertThat(fromT1).hasSize(1);
    }

    @Test
    void delete_removes_document() {
        store.put(collection, "id", Map.of("v", 1));
        assertThat(store.delete(collection, "id")).isTrue();
        assertThat(store.delete(collection, "id")).isFalse();
        assertThat(store.get(collection, "id")).isEmpty();
    }
}
