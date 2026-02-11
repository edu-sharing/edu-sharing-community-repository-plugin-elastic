package org.edu_sharing.elasticsearch.tracker;

import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import lombok.Setter;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeData;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

public class DefaultTransactionTracker extends TransactionTrackerBase {

    @Setter
    private List<String> globalTypeFilter;

    @Setter
    private List<String> workspaceTypes;

    @Setter
    protected List<String> indexStoreRefs;
    private final List<String> workspaceSubTypes = Arrays.asList("ccm:io", "ccm:rating", "ccm:comment", "ccm:usage", "ccm:collection_proposal");


    private final Logger logger = LoggerFactory.getLogger(DefaultTransactionTracker.class);

    @Setter
    @Value("${tracker.fetch.size.alfresco}")
    int fetchSizeAlfresco;

    @Setter
    @Value("${tracker.bulk.size.elastic}")
    int bulkSizeElastic;

    public DefaultTransactionTracker(){
        super();
    }



    @Override
    public void trackNodes(List<Node> nodes) throws IOException {

        //filter stores
        nodes = nodes.stream()
                .filter(n -> indexStoreRefs.contains(Tools.getStoreRef(n.getNodeRef())))
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

        workspaceService.beforeDeleteCleanupCollectionReplicas(toDelete);
        workspaceService.delete(toDelete);

        // index nodes
        //some transactions can have a lot of Nodes which can cause trouble on alfresco so use partitioning
        Collection<List<Node>> partitions = Partition.getPartitions(nodes, fetchSizeAlfresco);

        logger.info("getNodeMetadata start. partitions: {}",partitions.size());
        List<NodeMetadata> nodeData = Collections.synchronizedList(new ArrayList<>());
        this.threadUtil.runThreaded(
                partitions.stream().toList(),
                p -> nodeData.addAll(alfClient.getNodeMetadata(p)),
                true,
                true);
        logger.info("getNodeMetadata done. partitions: {} nodeMetadata: {}",partitions.size(), nodeData.size());
        indexNodesMetadata(nodeData);
    }

    public void indexNodesMetadata(List<NodeMetadata> nodeMetadata) throws IOException {
        // filter not allowed types:
        logger.info("filter disallowed types");
        nodeMetadata = nodeMetadata.stream()
                .peek(d -> { if (!isAllowedType(d)) logger.info("ignoring type: {}", d.getType()); })
                .filter(this::isAllowedType)
                .toList();

        logger.info("find and add nodes with subobject changes");
        nodeMetadata = addNodesWithSubobjectChanges(nodeMetadata);
        logger.info("transform to NodeData");
        List<NodeData> toIndex = getNodeData(nodeMetadata);
        logger.info("translate i18n");
        List<NodeData> toIndexNodes = translate(toIndex);

        // io's, maps
        logger.info("index user nodes size:{}", toIndexNodes.size());
        updateNodes(toIndexNodes);
        // refresh index so that collections will be found by cacheCollections process
        logger.info("starting refresh index");
        workspaceService.refreshWorkspace();
        logger.info("finished refresh index");
    }



    private void updateNodes(List<NodeData> toIndex) throws IOException {
        Collection<List<NodeData>> partitioned = Partition.getPartitions(toIndex, bulkSizeElastic);
        int pIdx = 0;


        for (List<NodeData> p : partitioned) {
            List<BulkOperation> operations = Collections.synchronizedList(new ArrayList<>());
            logger.info("starting partition: {}, partitionSize: {}",pIdx, p.size());
            this.threadUtil.runThreaded(p,
                    nodes -> workspaceService.addBulkOperation(nodes, operations),
                    true,
                    true);
            logger.info("index bulkOperations: {}",operations.size());
            workspaceService.index(operations);
            logger.info("finished partition {}", pIdx);
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
        Collection<List<NodeMetadata>> partitions = Partition.getPartitions(nodeMetadata, fetchSizeAlfresco);
        List<NodeData> nodeData = Collections.synchronizedList(new ArrayList<>());
        this.threadUtil.runThreaded(partitions.stream().toList(),p -> nodeData.addAll(alfClient.getNodeData(p)),true,true);
        return nodeData;
    }

    @NotNull
    private List<NodeMetadata> addNodesWithSubobjectChanges(List<NodeMetadata> nodeData) throws IOException {
        List<NodeMetadata> toIndexMd = new ArrayList<>();
        List<Node> ioSubobjectChange = new ArrayList<>();

        for (NodeMetadata data : nodeData) {

            //force reindex of parent io to get subobjects
            if (workspaceSubTypes.contains(data.getType())
                    && (!data.getType().equals("ccm:io") || data.getAspects().contains("ccm:io_childobject"))
                    && CCConstants.STORE_WORKSPACES_SPACES.equals(Tools.getStoreRef(data.getNodeRef()))) {

                String[] splitted = data.getPaths().get(0).getApath().split("/");
                String parentId = splitted[splitted.length - 1];
                Serializable value = workspaceService.getProperty(CCConstants.STORE_WORKSPACES_SPACES + "/" + parentId, "dbid");

                if (value != null) {
                    long parentDbid = ((Number) value).longValue();
                    logger.info("FOUND PARENT IO WITH {}", parentDbid);
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

    public List<NodeMetadata> filterByNodeTypes(List<NodeMetadata> nodeData, String... types ) {
        return nodeData.stream().filter(n -> Arrays.asList(types).contains(n.getType())).collect(Collectors.toList());
    }

    public boolean isAllowedType(NodeMetadata nodeMetadata) {
        if (!workspaceTypes.isEmpty()) {
            String type = nodeMetadata.getType();
            return workspaceTypes.contains(type);
        }
        return true;
    }
}

