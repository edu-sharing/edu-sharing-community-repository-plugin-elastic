package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.search.Hit;

import java.io.IOException;
import java.util.List;

public interface SearchHitsRunner {

    default <T> void run(Query query, Class<T> modelClass, IOConsumer<Hit<T>> hitConsumer) throws IOException {
        this.run(query, 5, modelClass, hitConsumer);
    }

    default <T> void run(Query query, int pageSize, Class<T> modelClass, IOConsumer<Hit<T>> hitConsumer) throws IOException {
        this.run(query, pageSize, null, null, modelClass, hitConsumer);
    }

    default <T> void run(Query query, int pageSize, Integer maxResultsSize, List<SortOptions> sortOptions, Class<T> modelClass, IOConsumer<Hit<T>> hitConsumer) throws IOException {
        this.scroll(query, pageSize, maxResultsSize, "1m", null, sortOptions, modelClass, hitConsumer);
    }

    <T> void scroll(Query query, int pageSize, Integer maxResultsSize, String scrollTimeout, List<String> excludes, List<SortOptions> sortOptions, Class<T> modelClass, SearchHitsRunner.IOConsumer<Hit<T>> hitConsumer) throws IOException;

    @FunctionalInterface
    interface IOConsumer<T> {
        void accept(T t) throws IOException;
    }
}
