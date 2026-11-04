package org.edu_sharing.elasticsearch.tracker.scope;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.edu_sharing.api.scope.ScopeApi;
import org.edu_sharing.elasticsearch.edu_sharing.api.scope.ScopeChangeData;
import org.edu_sharing.elasticsearch.edu_sharing.api.scope.ScopeIndexFragment;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTrackingSupport;
import org.edu_sharing.elasticsearch.tracker.core.generic.TimedData;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Scope tracker (E2): reads the change feed of the repository (no alfresco transactions are involved, ADR-1),
 * fetches the current scope fields of the changed nodes and their child objects and writes them to the node and its
 * published copies. A deleted scope group is removed from all documents.
 */
@Slf4j
@RequiredArgsConstructor
public class ScopeTrackingSupport implements GenericTrackingSupport<ScopeChangeData> {

    /** nodes per index data request */
    static final int INDEX_BATCH_SIZE = 200;

    private final ScopeApi scopeApi;
    private final WorkspaceService workspaceService;

    @Override
    public List<TimedData<ScopeChangeData>> getData(OffsetDateTime fromTimeStamp, Long afterId, OffsetDateTime toTimeStamp, int batchSize) {
        // the epoch start is the tracker's "no cursor yet"
        OffsetDateTime after = fromTimeStamp == null || fromTimeStamp.toInstant().toEpochMilli() == 0 ? null : fromTimeStamp;
        List<ScopeChangeData> changes = scopeApi.getChanges(after, afterId, toTimeStamp, batchSize);
        if (changes == null) {
            throw new IllegalStateException("no scope change feed response from repository");
        }
        return changes.stream()
                .map(change -> new TimedData<>(change, change.getAt().toInstant().toEpochMilli(), change.getSeq()))
                .toList();
    }

    @Override
    public void onHandleData(List<ScopeChangeData> trackingData) throws IOException {
        Set<String> deletedScopes = new LinkedHashSet<>();
        Set<String> nodeIds = new LinkedHashSet<>();
        for (ScopeChangeData change : trackingData) {
            if (change.getType() == ScopeChangeData.Type.SCOPE_DELETED) {
                deletedScopes.add(change.getScopeId());
            } else if (change.getNodeIds() != null) {
                nodeIds.addAll(change.getNodeIds());
            }
        }

        for (String scopeId : deletedScopes) {
            long updated = workspaceService.removeScope(scopeId);
            log.info("scope {} removed from {} documents", scopeId, updated);
        }

        if (nodeIds.isEmpty()) {
            return;
        }
        // child objects inherit the entries of their parent
        nodeIds.addAll(workspaceService.findChildObjectIds(nodeIds));

        List<String> ids = new ArrayList<>(nodeIds);
        int missing = 0;
        for (int i = 0; i < ids.size(); i += INDEX_BATCH_SIZE) {
            List<String> batch = ids.subList(i, Math.min(i + INDEX_BATCH_SIZE, ids.size()));
            List<ScopeIndexFragment> fragments = scopeApi.getIndexData(batch);
            if (fragments == null) {
                throw new IOException("no scope index data response from repository");
            }
            for (ScopeIndexFragment fragment : fragments) {
                // a node that is not indexed yet gets its scope fields when the main tracker indexes it (E3)
                if (workspaceService.updateNodesWithScopes(fragment.getNodeId(), fragment.toFields()).primaryMissing()) {
                    missing++;
                }
            }
        }
        log.info("scopes written: changes={} nodes={} documentMissing={} scopesRemoved={}", trackingData.size(), ids.size(), missing, deletedScopes.size());
        workspaceService.refreshWorkspace();
    }
}
