package org.edu_sharing.elasticsearch.tracker.collection;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.elasticsearch.core.NodeFailureService;
import org.edu_sharing.elasticsearch.elasticsearch.utils.ElasticErrorClassifier;
import org.edu_sharing.elasticsearch.elasticsearch.utils.utils.NodeMetadataSimple;
import org.edu_sharing.elasticsearch.tracker.core.AbstractAlfTransactionTracker;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.rag.RagAccessSync;
import org.edu_sharing.elasticsearch.tracker.utils.Partition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class CollectionSyncTracker extends AbstractAlfTransactionTracker<AlfTransactionTrackerProperties> {

    /**
     * Empty unless the RAG projection is switched on. Membership changes never touch the original
     * node, so without this the chunk index would keep the access it had when the original was last
     * embedded.
     */
    @Setter(onMethod_ = @Autowired)
    private ObjectProvider<RagAccessSync> ragAccessSync;

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
            try {
                if(nodeMetadata.getType().equals("ccm:collection_proposal") || nodeMetadata.getType().equals("ccm:usage")){
                    workspaceService.indexCollections(nodeMetadata);
                }
                NodeMetadataSimple nodeMetadataSimple = new NodeMetadataSimple(nodeMetadata);
                if(nodeMetadata.getType().equals("ccm:io")){
                    workspaceService.onUpdateRefreshUsageCollectionReplicas(nodeMetadataSimple,true,false);
                }
                if(nodeMetadata.getType().equals("ccm:map")){
                    workspaceService.syncCollectionReplicas(nodeMetadataSimple, getName());
                }
            } catch (ElasticsearchException e) {
                // a node the main tracker could not create must not block the whole batch. only skip
                // failures that belong to the document - anything else (connection, cluster, unknown)
                // is propagated so the transaction marker stays put and the batch is retried
                if (!ElasticErrorClassifier.isNodeLevel(e)) {
                    throw e;
                }
                log.warn("skipping node {} (dbid {}, txnId {}, type {}): [{}] {}",
                        nodeMetadata.getNodeRef(), nodeMetadata.getId(), nodeMetadata.getTxnId(),
                        nodeMetadata.getType(), ElasticErrorClassifier.errorType(e), e.getMessage());
                nodeFailureService.record(new NodeFailureService.NodeFailure(getName(), getName(),
                        "update", nodeMetadata.getId(), nodeMetadata.getNodeRef(),
                        nodeMetadata.getType(), nodeMetadata.getTxnId()), e);
            }
        }

        refreshRagAccess(filtered);
    }

    /**
     * Recomputes the chunk index's access for the originals whose collection membership just moved.
     * <p>
     * Runs after the replicas were written and the index refreshed, because the new access is read
     * back from the workspace document. The originals are found the same way the replicas were: a
     * usage or a collection reference names the original it belongs to.
     */
    private void refreshRagAccess(List<NodeMetadata> touched) throws IOException {
        if (ragAccessSync == null || ragAccessSync.getIfAvailable() == null || touched.isEmpty()) {
            return;
        }
        Set<String> originals = new LinkedHashSet<>();
        for (NodeMetadata node : touched) {
            if ("ccm:io".equals(node.getType())) {
                originals.add(Tools.getUUID(node.getNodeRef()));
            }
        }
        if (originals.isEmpty()) {
            return;
        }
        workspaceService.refreshWorkspace();
        try {
            ragAccessSync.getObject().onCollectionMembershipChanged(originals);
        } catch (Exception e) {
            // same reasoning as in AclTracker: an optional projection must not abort the run that
            // has already written the workspace index, or its cursor never advances
            log.error("rag access refresh failed for {} nodes - the chunk index keeps its previous "
                    + "access until these nodes are touched again", originals.size(), e);
        }
    }
}
