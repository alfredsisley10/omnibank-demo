package com.omnibank.shared.nosql.inmemory;

import com.omnibank.shared.nosql.DocumentStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-local map-backed implementation of {@link DocumentStore}. Used
 * by unit tests and as the default profile when MongoDB is not available.
 *
 * <p>Uses a {@link TreeMap} per collection so iteration order is stable
 * (id ascending) — which makes test assertions far easier and keeps the
 * generated AppMap traces visually consistent run-to-run.</p>
 */
public class InMemoryDocumentStore implements DocumentStore {

    private final ConcurrentMap<String, Map<String, Map<String, Object>>> data = new ConcurrentHashMap<>();

    @Override
    public void put(String collection, String id, Map<String, Object> document) {
        Objects.requireNonNull(collection);
        Objects.requireNonNull(id);
        Objects.requireNonNull(document);
        Map<String, Map<String, Object>> coll = data.computeIfAbsent(collection,
                k -> java.util.Collections.synchronizedMap(new TreeMap<>()));
        synchronized (coll) {
            coll.put(id, deepCopy(document));
        }
    }

    @Override
    public Optional<Map<String, Object>> get(String collection, String id) {
        Map<String, Map<String, Object>> coll = data.get(collection);
        if (coll == null) return Optional.empty();
        synchronized (coll) {
            Map<String, Object> doc = coll.get(id);
            return doc == null ? Optional.empty() : Optional.of(deepCopy(doc));
        }
    }

    @Override
    public void insertOnce(String collection, String id, Map<String, Object> document) {
        Objects.requireNonNull(collection);
        Objects.requireNonNull(id);
        Objects.requireNonNull(document);
        Map<String, Map<String, Object>> coll = data.computeIfAbsent(collection,
                k -> java.util.Collections.synchronizedMap(new TreeMap<>()));
        synchronized (coll) {
            if (coll.containsKey(id)) {
                throw new IllegalStateException(
                        "Duplicate document id in collection '" + collection + "': " + id);
            }
            coll.put(id, deepCopy(document));
        }
    }

    @Override
    public boolean delete(String collection, String id) {
        Map<String, Map<String, Object>> coll = data.get(collection);
        if (coll == null) return false;
        synchronized (coll) {
            return coll.remove(id) != null;
        }
    }

    @Override
    public List<Map<String, Object>> find(String collection, Map<String, Object> filter, int limit) {
        Map<String, Map<String, Object>> coll = data.get(collection);
        if (coll == null) return List.of();
        var out = new ArrayList<Map<String, Object>>();
        synchronized (coll) {
            for (Map<String, Object> doc : coll.values()) {
                if (matches(doc, filter)) {
                    out.add(deepCopy(doc));
                    if (limit > 0 && out.size() >= limit) break;
                }
            }
        }
        return List.copyOf(out);
    }

    @Override
    public long count(String collection, Map<String, Object> filter) {
        Map<String, Map<String, Object>> coll = data.get(collection);
        if (coll == null) return 0;
        long total = 0;
        synchronized (coll) {
            for (Map<String, Object> doc : coll.values()) {
                if (matches(doc, filter)) total++;
            }
        }
        return total;
    }

    @Override
    public List<Map<String, Object>> since(String collection, String timestampField, Instant since, int limit) {
        Map<String, Map<String, Object>> coll = data.get(collection);
        if (coll == null) return List.of();
        var out = new ArrayList<Map<String, Object>>();
        synchronized (coll) {
            for (Map<String, Object> doc : coll.values()) {
                Object raw = doc.get(timestampField);
                Instant ts = parseInstant(raw);
                if (ts == null) continue;
                if (!ts.isBefore(since)) {
                    out.add(deepCopy(doc));
                }
            }
        }
        out.sort(Comparator.comparing(d -> parseInstant(d.get(timestampField)),
                Comparator.nullsLast(Comparator.naturalOrder())));
        if (limit > 0 && out.size() > limit) {
            return List.copyOf(out.subList(0, limit));
        }
        return List.copyOf(out);
    }

    @Override
    public List<String> collections() {
        return List.copyOf(data.keySet());
    }

    private static boolean matches(Map<String, Object> doc, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) return true;
        for (Map.Entry<String, Object> e : filter.entrySet()) {
            if (!Objects.equals(doc.get(e.getKey()), e.getValue())) return false;
        }
        return true;
    }

    private static Instant parseInstant(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Instant i) return i;
        if (raw instanceof CharSequence cs) {
            try { return Instant.parse(cs.toString()); } catch (Exception ignore) { return null; }
        }
        if (raw instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> in) {
        var out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) {
                out.put(e.getKey(), deepCopy((Map<String, Object>) m));
            } else if (v instanceof List<?> l) {
                out.put(e.getKey(), List.copyOf(l));
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }
}
