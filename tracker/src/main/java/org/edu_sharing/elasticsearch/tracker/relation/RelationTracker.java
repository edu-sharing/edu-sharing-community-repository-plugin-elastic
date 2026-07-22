package org.edu_sharing.elasticsearch.tracker.relation;

import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.core.AbstractAlfTransactionTracker;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.edu_sharing.elasticsearch.tracker.utils.Partition;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.RelationData;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RelationTracker extends AbstractAlfTransactionTracker<AlfTransactionTrackerProperties> {

    public RelationTracker(AlfTransactionTrackerProperties relationTrackerProperties) {
        super(relationTrackerProperties);
    }

    @Override
    public void trackNodes(List<Node> nodes) throws IOException {
        
        Map<Node, List<RelationData>> nodeRelations = new ConcurrentHashMap<>();

        Collection<List<Node>> partitions = Partition.getPartitions(nodes, props.getFetchSizeAlfresco());
        this.threadUtil.runThreaded(
                partitions,
                partition -> partition.forEach(node -> {
                    List<RelationData> relationData = eduSharingService.getRelations(Tools.getUUID(node.getNodeRef()));
                    nodeRelations.put(node, relationData);
                }),
                true,
                true);

        threadUtil.runThreaded(nodeRelations.entrySet(), entry -> updateNodesWithRelations(entry.getKey(), entry.getValue()), true, true);
        workspaceService.refreshWorkspace();
    }

    private void updateNodesWithRelations(Node node, List<RelationData> relationDataList) {
        workspaceService.updateNodesWithRelations(Tools.getUUID(node.getNodeRef()), relationDataList);
    }
}
