package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Constructor;

@Configuration
@RequiredArgsConstructor
public class StatusIndexServiceFactory {

    private final ElasticsearchClient client;

    public <T> StatusIndexServiceInterface<T> createStateService(Class<T> statusClass, String documentId, String index) {
        Constructor<T> defaultConstructor;
        try {
            defaultConstructor = statusClass.getConstructor();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("No default constructor found for status class " + statusClass.getName());
        }

        return new StatusIndexService<>(index, client, defaultConstructor::newInstance, documentId, statusClass);
    }
}
