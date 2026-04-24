package com.omnibank.shared.nosql;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * MongoDB-backed implementation of {@link DocumentStore}. Stores BSON
 * documents and exposes the same surface as the in-memory store so
 * controllers and services don't need to know which one is wired up.
 *
 * <p>Documents are written under a string {@code _id} to keep parity
 * with the in-memory store's id semantics.</p>
 */
public class MongoDocumentStore implements DocumentStore {

    private final MongoTemplate template;

    public MongoDocumentStore(MongoTemplate template) {
        this.template = Objects.requireNonNull(template, "template");
    }

    @Override
    public void put(String collection, String id, Map<String, Object> document) {
        Document update = new Document(document);
        update.put("_id", id);
        Query q = new Query(Criteria.where("_id").is(id));
        Update upd = Update.fromDocument(new Document("$set", update));
        template.upsert(q, upd, collection);
    }

    @Override
    public Optional<Map<String, Object>> get(String collection, String id) {
        Document doc = template.findById(id, Document.class, collection);
        if (doc == null) return Optional.empty();
        return Optional.of(toMap(doc));
    }

    @Override
    public void insertOnce(String collection, String id, Map<String, Object> document) {
        if (template.findById(id, Document.class, collection) != null) {
            throw new IllegalStateException(
                    "Duplicate document id in collection '" + collection + "': " + id);
        }
        Document doc = new Document(document);
        doc.put("_id", id);
        template.insert(doc, collection);
    }

    @Override
    public boolean delete(String collection, String id) {
        Query q = new Query(Criteria.where("_id").is(id));
        return template.remove(q, collection).getDeletedCount() > 0;
    }

    @Override
    public List<Map<String, Object>> find(String collection, Map<String, Object> filter, int limit) {
        Query q = new Query(buildCriteria(filter));
        if (limit > 0) q.limit(limit);
        List<Document> docs = template.find(q, Document.class, collection);
        var out = new ArrayList<Map<String, Object>>(docs.size());
        for (Document d : docs) out.add(toMap(d));
        return List.copyOf(out);
    }

    @Override
    public long count(String collection, Map<String, Object> filter) {
        Query q = new Query(buildCriteria(filter));
        return template.count(q, collection);
    }

    @Override
    public List<Map<String, Object>> since(String collection, String timestampField, Instant since, int limit) {
        Criteria c = Criteria.where(timestampField).gte(since);
        Query q = new Query(c).with(org.springframework.data.domain.Sort.by(timestampField));
        if (limit > 0) q.limit(limit);
        List<Document> docs = template.find(q, Document.class, collection);
        var out = new ArrayList<Map<String, Object>>(docs.size());
        for (Document d : docs) out.add(toMap(d));
        return List.copyOf(out);
    }

    @Override
    public List<String> collections() {
        return List.copyOf(template.getCollectionNames());
    }

    private static Criteria buildCriteria(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) {
            return new Criteria();
        }
        Criteria c = null;
        for (Map.Entry<String, Object> e : filter.entrySet()) {
            Criteria leaf = Criteria.where(e.getKey()).is(e.getValue());
            c = (c == null) ? leaf : c.andOperator(leaf);
        }
        return c;
    }

    private static Map<String, Object> toMap(Document d) {
        var out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : d.entrySet()) {
            if ("_id".equals(e.getKey()) && e.getValue() != null) {
                out.put("_id", e.getValue().toString());
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
