package org.edu_sharing.elasticsearch.tracker.collection;

import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.elasticsearch.utils.utils.NodeMetadataSimple;
import org.edu_sharing.elasticsearch.tracker.core.AbstractAlfTransactionTracker;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CollectionSyncTracker extends AbstractAlfTransactionTracker<AlfTransactionTrackerProperties> {

    public CollectionSyncTracker(AlfTransactionTrackerProperties collectionSyncTrackerProps) {
        super(collectionSyncTrackerProps);
    }

    @Override
    public void trackNodes(List<Node> nodes) throws IOException {
        log.info("called!");
        Set<String> nodeIds = nodes.stream().map(Node::getNodeRef).collect(Collectors.toSet());

        Map<String, Map<String, Object>> result = workspaceService.getSourceMap(nodeIds);

        nodeIds.removeAll(result.keySet());
        nodeIds.forEach(nodeId -> log.info("collection nodeId:{} not found in index", nodeId));

        result.forEach((nodeId, sourceMap) -> {
            try {
                workspaceService.syncCollectionReplicas(new NodeMetadataSimple(sourceMap));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
