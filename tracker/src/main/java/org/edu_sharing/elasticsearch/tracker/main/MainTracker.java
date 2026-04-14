package org.edu_sharing.elasticsearch.tracker.main;

import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeData;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.core.AbstractAlfTransactionTracker;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.edu_sharing.elasticsearch.tracker.utils.Partition;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class MainTracker extends AbstractAlfTransactionTracker<AlfTransactionTrackerProperties> {

    public MainTracker(AlfTransactionTrackerProperties mainTrackerProperties) {
        super(mainTrackerProperties);
    }

    @Override
    public void trackNodes(List<Node> nodes) throws IOException {

        //filter stores
        nodes = nodes.stream()
                .filter(n -> props.getIndexStoreRefs().contains(Tools.getStoreRef(n.getNodeRef())))
                .collect(Collectors.toList());

        if (nodes.isEmpty()) {
            return;
        }

        // collect deletes
        List<Node> toDelete = nodes.stream()
                .filter(node -> node.getStatus().equals("d"))
                .collect(Collectors.toList());

        //filter deletes
        nodes = nodes.stream()
                .filter(n -> !n.getStatus().equals("d"))
                .collect(Collectors.toList());

        workspaceService.beforeDeleteCleanupChildrenReplicas(toDelete);
        workspaceService.delete(toDelete);

        // index nodes
        //some transactions can have a lot of Nodes which can cause trouble on alfresco so use partitioning
        Collection<List<Node>> partitions = Partition.getPartitions(nodes, props.getFetchSizeAlfresco());

        log.info("getNodeMetadata start. partitions: {}", partitions.size());
        List<NodeMetadata> nodeData = Collections.synchronizedList(new ArrayList<>());
        this.threadUtil.runThreaded(
                partitions.stream().toList(),
                p -> nodeData.addAll(alfClient.getNodeMetadata(p)),
                true,
                true);
        log.info("getNodeMetadata done. partitions: {} nodeMetadata: {}", partitions.size(), nodeData.size());
        indexNodesMetadata(nodeData);
    }

    private void indexNodesMetadata(List<NodeMetadata> nodeMetadata) throws IOException {
        // filter not allowed types:
        log.info("filter disallowed types");
        nodeMetadata = nodeMetadata.stream()
                .peek(d -> {
                    if (!isAllowedType(d)) log.info("ignoring type: {}", d.getType());
                })
                .filter(this::isAllowedType)
                .toList();

        log.info("find and add nodes with subobject changes");
        nodeMetadata = addNodesWithSubobjectChanges(nodeMetadata);
        log.info("transform to NodeData");
        List<NodeData> toIndex = getNodeData(nodeMetadata);
        log.info("translate i18n");
        List<NodeData> toIndexNodes = translate(toIndex);

        // io's, maps
        log.info("index user nodes size:{}", toIndexNodes.size());
        updateNodes(toIndexNodes);
        // refresh index so that collections will be found by cacheCollections process
        log.info("starting refresh index");
        workspaceService.refreshWorkspace();
        log.info("finished refresh index");
    }


    private void updateNodes(List<NodeData> toIndex) throws IOException {
        Collection<List<NodeData>> partitioned = Partition.getPartitions(toIndex, props.getBulkSizeElastic());
        int pIdx = 0;


        for (List<NodeData> p : partitioned) {
            List<BulkOperation> operations = Collections.synchronizedList(new ArrayList<>());
            log.info("starting partition: {}, partitionSize: {}", pIdx, p.size());
            this.threadUtil.runThreaded(p,
                    nodes -> workspaceService.addBulkOperation(nodes, operations),
                    true,
                    true);
            log.info("index bulkOperations: {}", operations.size());
            workspaceService.index(operations);
            log.info("finished partition {}", pIdx);
            pIdx++;
        }
    }

    private List<NodeData> translate(List<NodeData> toIndex) throws IOException {
        //skipping preview and valuespace translation for archived nodes
        List<NodeData> toTranslate = Collections.synchronizedList(toIndex.stream().filter(n -> !n.getNodeMetadata().getNodeRef().startsWith(CCConstants.ARCHIVE_STOREREF)).toList());
        this.threadUtil.runThreaded(toTranslate,
                n -> eduSharingClient.translateValuespaceProps(n),
                false,
                false);
        return toIndex;
    }

    private List<NodeData> getNodeData(List<NodeMetadata> nodeMetadata) throws IOException {
        Collection<List<NodeMetadata>> partitions = Partition.getPartitions(nodeMetadata, props.getFetchSizeAlfresco());
        List<NodeData> nodeData = Collections.synchronizedList(new ArrayList<>());
        this.threadUtil.runThreaded(partitions.stream().toList(), p -> nodeData.addAll(alfClient.getNodeData(p)), true, true);
        return nodeData;
    }

    @NotNull
    private List<NodeMetadata> addNodesWithSubobjectChanges(List<NodeMetadata> nodeData) throws IOException {
        List<NodeMetadata> toIndexMd = new ArrayList<>();
        List<Node> ioSubobjectChange = new ArrayList<>();

        for (NodeMetadata data : nodeData) {

            //force reindex of parent io to get subobjects
            if (props.getWorkspaceSubTypes().contains(data.getType())
                    && (!data.getType().equals("ccm:io") || data.getAspects().contains("ccm:io_childobject"))
                    && CCConstants.STORE_WORKSPACES_SPACES.equals(Tools.getStoreRef(data.getNodeRef()))) {

                String[] splitted = data.getPaths().get(0).getApath().split("/");
                String parentId = splitted[splitted.length - 1];
                Serializable value = workspaceService.getProperty(CCConstants.STORE_WORKSPACES_SPACES + "/" + parentId, "dbid");

                if (value != null) {
                    long parentDbid = ((Number) value).longValue();
                    log.info("FOUND PARENT IO WITH {}", parentDbid);
                    //check if exists in list
                    if (nodeData.stream().noneMatch(n -> n.getId() == parentDbid)) {
                        Node n = new Node();
                        n.setId(parentDbid);
                        ioSubobjectChange.add(n);
                    }
                }//else io does not exist in index
            }

            toIndexMd.add(data);
        }

        if (!ioSubobjectChange.isEmpty()) {
            toIndexMd.addAll(alfClient.getNodeMetadata(ioSubobjectChange));
        }
        return toIndexMd;
    }

    private boolean isAllowedType(NodeMetadata nodeMetadata) {
        if (!props.getWorkspaceTypes().isEmpty()) {
            String type = nodeMetadata.getType();
            return props.getWorkspaceTypes().contains(type);
        }
        return true;
    }
}

