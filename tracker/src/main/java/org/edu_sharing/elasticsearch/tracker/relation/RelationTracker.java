package org.edu_sharing.elasticsearch.tracker.relation;

import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.core.AbstractAlfTransactionTracker;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.RelationData;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class RelationTracker extends AbstractAlfTransactionTracker<AlfTransactionTrackerProperties> {

    public RelationTracker(AlfTransactionTrackerProperties relationTrackerProperties) {
        super(relationTrackerProperties);
    }

    @Override
    public void trackNodes(List<Node> nodes) throws IOException {
        List<Node> relevant = filterIndexableNodes(nodes);
        if (relevant.isEmpty()) {
            return;
        }

        // keyed by UUID, not Node: filterIndexableNodes already deduplicated on that basis, and the
        // UUID is what actually identifies the Elasticsearch document we write to.
        Map<String, List<RelationData>> nodeRelations = new ConcurrentHashMap<>();

        this.threadUtil.runThreaded(
                relevant,
                node -> {
                    String nodeId = Tools.getUUID(node.getNodeRef());
                    List<RelationData> relationData = eduSharingService.getRelations(nodeId);
                    if (relationData == null) {
                        throw new IOException("no relation response from repository for node " + nodeId);
                    }
                    nodeRelations.put(nodeId, relationData);
                },
                true,
                true);

        AtomicInteger documentMissing = new AtomicInteger();
        AtomicInteger copyConflicts = new AtomicInteger();
        threadUtil.runThreaded(nodeRelations.entrySet(), entry -> {
            WorkspaceService.FieldUpdateOutcome outcome =
                    workspaceService.updateNodesWithRelations(entry.getKey(), entry.getValue());
            if (outcome.primaryMissing()) {
                documentMissing.incrementAndGet();
            }
            if (outcome.copiesConflicts() > 0) {
                copyConflicts.incrementAndGet();
            }
        }, true, true);

        log.info("relations written: nodes={} tracked={} documentMissing={} copyConflicts={}",
                nodes.size(), relevant.size(), documentMissing.get(), copyConflicts.get());

        workspaceService.refreshWorkspace();
    }
}
