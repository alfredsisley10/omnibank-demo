package com.omnibank.shared.nosql;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Abstract document persistence used by the Omnibank NoSQL projections.
 * Implementations include the in-process map-backed store for unit tests
 * and the MongoDB-backed store for integration / production use.
 *
 * <p>The contract is intentionally narrow — Omnibank uses NoSQL for
 * append-only event projections and high-cardinality lookups, NOT as a
 * primary system of record. That belongs to the SQL ledger. This is why
 * the API has no concept of optimistic locking or upsert-with-retry.</p>
 */
public interface DocumentStore {

    /** Persist a document, replacing any prior version with the same id. */
    void put(String collection, String id, Map<String, Object> document);

    /** Look up a single document by id. */
    Optional<Map<String, Object>> get(String collection, String id);

    /** Append-only insert; throws if an id collision would overwrite an existing document. */
    void insertOnce(String collection, String id, Map<String, Object> document);

    /** Remove a document. Returns true if something was deleted. */
    boolean delete(String collection, String id);

    /** Page through documents matching a fragmentary filter. */
    List<Map<String, Object>> find(String collection, Map<String, Object> filter, int limit);

    /** Count documents matching the filter. */
    long count(String collection, Map<String, Object> filter);

    /** All documents written into a collection on or after the supplied timestamp. */
    List<Map<String, Object>> since(String collection, String timestampField, Instant since, int limit);

    /** Return all known collection names (best-effort). */
    List<String> collections();
}
