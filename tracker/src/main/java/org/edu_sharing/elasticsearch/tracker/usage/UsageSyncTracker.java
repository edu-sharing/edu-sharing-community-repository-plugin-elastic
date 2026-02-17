package org.edu_sharing.elasticsearch.tracker.usage;

import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.tracker.core.AbstractAlfTransactionTracker;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
public class UsageSyncTracker extends AbstractAlfTransactionTracker<AlfTransactionTrackerProperties> {

    public UsageSyncTracker(AlfTransactionTrackerProperties usageTrackerProps) {
        super(usageTrackerProps);
    }

    @Override
    public void trackNodes(List<Node> nodes) throws IOException {
        List<NodeMetadata> nodeMetadatas = alfClient.getNodeMetadata(nodes);
        for(NodeMetadata nodeMetadata : nodeMetadatas) {
            workspaceService.indexCollections(nodeMetadata);
        }
    }
}
