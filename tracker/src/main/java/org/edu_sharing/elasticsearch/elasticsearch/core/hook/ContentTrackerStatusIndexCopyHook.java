package org.edu_sharing.elasticsearch.elasticsearch.core.hook;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.springframework.stereotype.Component;

/**
 * Hook that copies the MainTracker status index to ContentTracker status index if needed.
 * This ensures backward compatibility when migrating from MainTracker to ContentTracker.
 * <p>
 * Logic:
 * - If ContentTracker status index exists -> do nothing
 * - If MainTracker status index exists and ContentTracker doesn't -> copy MainTracker to ContentTracker
 */
@Slf4j
@Component
public class ContentTrackerStatusIndexCopyHook extends BaseStatusIndexCopyHook {

    public ContentTrackerStatusIndexCopyHook(ElasticsearchClient client, IndexConfiguration trackerState) {
        super(client, trackerState);
    }

    @Override
    protected String getSourceTrackerName() {
        return "mainTracker";
    }

    @Override
    protected String getTargetTrackerName() {
        return "contentTracker";
    }

    @Override
    public String getName() {
        return "contentTrackerStatusIndexCopyHook";
    }
}
