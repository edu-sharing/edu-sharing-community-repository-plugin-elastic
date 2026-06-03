package org.edu_sharing.elasticsearch.elasticsearch.core.hook;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.json.JsonData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;

import java.io.IOException;

/**
 * Abstract base class for hooks that copy status index documents from one tracker to another.
 * This is useful for backward compatibility when migrating or renaming trackers.
 * <p>
 * Subclasses must implement:
 * - {@link #getSourceTrackerName()} - the tracker to copy from
 * - {@link #getTargetTrackerName()} - the tracker to copy to
 * - {@link #getName()} - unique hook name
 * <p>
 * Logic:
 * - If target status index exists -> do nothing
 * - If source status index exists and target doesn't -> copy source to target
 */
@Slf4j
@RequiredArgsConstructor
public abstract class BaseStatusIndexCopyHook implements ApplicationStartupHook {

    private final ElasticsearchClient client;
    private final IndexConfiguration trackerState;

    /**
     * Get the name of the source tracker to copy from.
     *
     * @return the source tracker name (e.g., "mainTracker")
     */
    protected abstract String getSourceTrackerName();

    /**
     * Get the name of the target tracker to copy to.
     *
     * @return the target tracker name (e.g., "contentTracker")
     */
    protected abstract String getTargetTrackerName();

    @Override
    public void execute() throws IOException {
        String statusIndexName = trackerState.getIndex();
        String sourceTrackerName = getSourceTrackerName();
        String targetTrackerName = getTargetTrackerName();

        log.info("Checking if status index copy is needed (from '{}' to '{}')",
                sourceTrackerName, targetTrackerName);

        // Check if target status index exists
        boolean targetExists = statusIndexDocumentExists(statusIndexName, targetTrackerName);

        if (targetExists) {
            log.info("Status index for '{}' already exists, no copy needed", targetTrackerName);
            return;
        }

        log.info("Status index for '{}' does not exist", targetTrackerName);

        // Check if source status index exists
        boolean sourceExists = statusIndexDocumentExists(statusIndexName, sourceTrackerName);

        if (!sourceExists) {
            log.info("Status index for '{}' does not exist, no copy possible", sourceTrackerName);
            return;
        }

        log.info("Status index for '{}' exists, copying to '{}'",
                sourceTrackerName, targetTrackerName);

        // Copy the source status to target
        copyStatusIndex(statusIndexName, sourceTrackerName, targetTrackerName);

        log.info("Successfully copied status index from '{}' to '{}'",
                sourceTrackerName, targetTrackerName);
    }

    private boolean statusIndexDocumentExists(String indexName, String documentId) {
        try {
            GetResponse<JsonData> response = client.get(req -> req
                    .index(indexName)
                    .id(documentId), JsonData.class);

            return response.found();
        } catch (Exception e) {
            // If index doesn't exist or document not found, return false
            log.debug("Document '{}' not found in index '{}': {}",
                    documentId, indexName, e.getMessage());
            return false;
        }
    }

    private void copyStatusIndex(String indexName, String sourceDocumentId, String targetDocumentId)
            throws IOException {
        // Get the source document
        GetResponse<JsonData> sourceResponse = client.get(req -> req
                .index(indexName)
                .id(sourceDocumentId), JsonData.class);

        if (!sourceResponse.found() || sourceResponse.source() == null) {
            throw new IllegalStateException("Source document not found: " + sourceDocumentId);
        }

        JsonData sourceData = sourceResponse.source();

        // Index the data with the new document ID
        IndexResponse indexResponse = client.index(req -> req
                .index(indexName)
                .id(targetDocumentId)
                .document(sourceData));

        if (indexResponse.result() != Result.Created) {
            log.warn("Expected Created result, got: {}", indexResponse.result());
        }

        log.info("Copied status index document from '{}' to '{}' in index '{}'",
                sourceDocumentId, targetDocumentId, indexName);
    }

    @Override
    public int getOrder() {
        return 100; // Run after other potential hooks
    }
}
