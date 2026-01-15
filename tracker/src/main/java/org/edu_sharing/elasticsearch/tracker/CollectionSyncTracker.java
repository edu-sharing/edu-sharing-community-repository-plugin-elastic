package org.edu_sharing.elasticsearch.tracker;

import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.elasticsearch.utils.utils.NodeMetadataSimple;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
public class CollectionSyncTracker extends TransactionTrackerBase {

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
