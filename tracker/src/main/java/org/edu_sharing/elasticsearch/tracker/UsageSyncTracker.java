package org.edu_sharing.elasticsearch.tracker;

import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;

import java.io.IOException;
import java.util.List;

@Slf4j
public class UsageSyncTracker extends TransactionTrackerBase {

    @Override
    public void trackNodes(List<Node> nodes) throws IOException {
        List<NodeMetadata> nodeMetadatas = alfClient.getNodeMetadata(nodes);
        for(NodeMetadata nodeMetadata : nodeMetadatas) {
            workspaceService.indexCollections(nodeMetadata);
        }
    }
}
