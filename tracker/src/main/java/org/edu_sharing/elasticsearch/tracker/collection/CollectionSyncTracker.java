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

@Slf4j
@Component
public class CollectionSyncTracker extends AbstractAlfTransactionTracker<AlfTransactionTrackerProperties> {

    public CollectionSyncTracker(AlfTransactionTrackerProperties collectionSyncTrackerProps) {
        super(collectionSyncTrackerProps);
    }

    @Override
    public void trackNodes(List<Node> nodes) throws IOException {
        log.info("called!");
        for (Node node : nodes) {
            Map<String, Object> sourceMap = workspaceService.getSourceMap(node.getNodeRef());
            if(sourceMap == null){
                log.error("collection dbId: {} nodeRef:{} not found in index",node.getId(),node.getNodeRef());
            }
            workspaceService.syncCollectionReplicas(new NodeMetadataSimple(sourceMap));
        }
    }
}
