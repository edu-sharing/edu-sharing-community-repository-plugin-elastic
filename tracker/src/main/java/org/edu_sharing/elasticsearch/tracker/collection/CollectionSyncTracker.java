package org.edu_sharing.elasticsearch.tracker.collection;

import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.elasticsearch.utils.utils.NodeMetadataSimple;
import org.edu_sharing.elasticsearch.tracker.core.AbstractAlfTransactionTracker;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.edu_sharing.elasticsearch.tracker.utils.Partition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
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

        nodes.forEach(node -> {log.info("node {} status {} nodeRef {} ",node.getId(),node.getStatus(),node.getNodeRef());});

        // collect deletes
        List<Node> toDelete = nodes.stream()
                .filter(node -> node.getStatus().equals("d"))
                .toList();

        workspaceService.beforeDeleteCleanupCollectionReplicas(toDelete);

        //filter deletes
        nodes = nodes.stream()
                .filter(n -> !n.getStatus().equals("d"))
                .collect(Collectors.toList());


        Collection<List<Node>> partitions = Partition.getPartitions(nodes, props.getFetchSizeAlfresco());

        log.info("getNodeMetadata start. partitions: {} nodes: {}", partitions.size(), nodes.size());
        List<NodeMetadata> nodeData = Collections.synchronizedList(new ArrayList<>());
        this.threadUtil.runThreaded(
                partitions.stream().toList(),
                p -> nodeData.addAll(alfClient.getNodeMetadata(p)),
                true,
                true);

        List<NodeMetadata> filtered = nodeData.stream()
                .filter(n -> !(n.getType().equals("ccm:map") && !n.getAspects().contains("ccm:collection")))
                .filter(n -> !this.props.getWorkspaceSubTypes().contains(n.getType()))
                .toList();


        for(NodeMetadata nodeMetadata : filtered) {
            log.info("CollectionSync for type: {} nodeRef: {}", nodeMetadata.getType(),nodeMetadata.getNodeRef());
            if(nodeMetadata.getType().equals("ccm:collection_proposal") || nodeMetadata.getType().equals("ccm:usage")){
                workspaceService.indexCollections(nodeMetadata);
            }
            NodeMetadataSimple nodeMetadataSimple = new NodeMetadataSimple(nodeMetadata);
            if(nodeMetadata.getType().equals("ccm:io")){
                workspaceService.onUpdateRefreshUsageCollectionReplicas(nodeMetadataSimple,true,false);
            }
            if(nodeMetadata.getType().equals("ccm:map")){
                workspaceService.syncCollectionReplicas(nodeMetadataSimple);
            }
        }
    }
}
