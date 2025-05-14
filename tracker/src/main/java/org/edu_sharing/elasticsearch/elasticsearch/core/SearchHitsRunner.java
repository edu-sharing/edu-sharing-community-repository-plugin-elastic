package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.function.Consumer;

public final class SearchHitsRunner {

    private static final Logger logger = LoggerFactory.getLogger(SearchHitsRunner.class);

    private final WorkspaceService workspaceService;
    public SearchHitsRunner(WorkspaceService workspaceService){
        this.workspaceService = workspaceService;
    }

    public void run(Query query, Consumer<Hit<Map>> hitConsumer)throws IOException {
        this.run(query,5, hitConsumer);
    }

    public void run(Query query, int pageSize, Consumer<Hit<Map>> hitConsumer)throws IOException {
        this.run(query,pageSize, null, hitConsumer);
    }

    public void run(Query query, int pageSize, Integer maxResultsSize,  Consumer<Hit<Map>> hitConsumer)throws IOException {
        workspaceService.scroll(query,pageSize, maxResultsSize,"1m",null,hitConsumer);
    }
}
