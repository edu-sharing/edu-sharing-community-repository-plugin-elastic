package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public final class SearchHitsRunner  {

    private static final Logger logger = LoggerFactory.getLogger(SearchHitsRunner.class);

    private final WorkspaceService workspaceService;
    public SearchHitsRunner(WorkspaceService workspaceService){
        this.workspaceService = workspaceService;
    }

    public <T> void run(Query query, Class<T> modelClass, IOConsumer<Hit<T>> hitConsumer) throws IOException {
        this.run(query,5, modelClass, hitConsumer);
    }

    public <T> void run(Query query, int pageSize, Class<T> modelClass, IOConsumer<Hit<T>> hitConsumer) throws IOException {
        this.run(query,pageSize, null,null,modelClass, hitConsumer);
    }

    public <T> void run(Query query, int pageSize, Integer maxResultsSize, List<SortOptions> sortOptions, Class<T> modelClass, IOConsumer<Hit<T>> hitConsumer) throws IOException {
        workspaceService.scroll(query,pageSize, maxResultsSize,"1m",null,sortOptions,modelClass,hitConsumer);
    }

    @FunctionalInterface
    public interface IOConsumer<T> {
        void accept(T t) throws IOException;
    }
}
