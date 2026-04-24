package com.omnibank.shared.nosql;

import com.omnibank.shared.nosql.inmemory.InMemoryDocumentStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Wires the appropriate {@link DocumentStore} into the Spring context.
 *
 * <p>Activation rules:
 * <ul>
 *   <li>If {@code omnibank.nosql.mongo.enabled=true} <b>and</b> a
 *       {@link MongoTemplate} bean is available, a Mongo-backed store is
 *       registered.</li>
 *   <li>Otherwise an in-memory store is registered. Tests can opt into
 *       Mongo via Testcontainers + the property flag without changing
 *       any production code paths.</li>
 * </ul>
 */
@Configuration
public class NoSqlConfig {

    @Bean
    @ConditionalOnProperty(name = "omnibank.nosql.mongo.enabled", havingValue = "true")
    public DocumentStore mongoDocumentStore(MongoTemplate template) {
        return new MongoDocumentStore(template);
    }

    @Bean
    @ConditionalOnMissingBean(DocumentStore.class)
    public DocumentStore inMemoryDocumentStore() {
        return new InMemoryDocumentStore();
    }
}
