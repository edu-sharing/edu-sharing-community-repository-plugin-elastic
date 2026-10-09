package org.edu_sharing.elasticsearch.tracker.scope;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.edu_sharing.api.scope.ScopeApi;
import org.edu_sharing.elasticsearch.edu_sharing.api.scope.ScopeIndexFragment;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * Writes the scope fields of documents that were just created by the main tracker (E3). Without this, a node restored
 * from the trashcan or indexed for the first time after its scope entry was created would lose its scope data until
 * the next change of the entry.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScopeFieldsWriter {

    private final ScopeApi scopeApi;
    private final WorkspaceService workspaceService;

    /**
     * failures are logged and do not fail the indexing of the nodes themselves
     */
    public void writeForNewDocuments(Collection<String> nodeIds) {
        if (nodeIds.isEmpty()) {
            return;
        }
        try {
            List<String> ids = List.copyOf(nodeIds);
            for (int i = 0; i < ids.size(); i += ScopeTrackingSupport.INDEX_BATCH_SIZE) {
                List<ScopeIndexFragment> fragments = scopeApi.getIndexData(ids.subList(i, Math.min(i + ScopeTrackingSupport.INDEX_BATCH_SIZE, ids.size())));
                if (fragments == null) {
                    continue;
                }
                for (ScopeIndexFragment fragment : fragments) {
                    // nodes without any entry keep their documents as they are
                    if (fragment.getScopeIds() != null && !fragment.getScopeIds().isEmpty()) {
                        workspaceService.updateNodesWithScopes(fragment.getNodeId(), fragment.toFields());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("could not write scope fields of {} new documents: {}", nodeIds.size(), e.getMessage(), e);
        }
    }
}
