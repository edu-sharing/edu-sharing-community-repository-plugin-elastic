package org.edu_sharing.elasticsearch.tracker;

import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import io.micrometer.core.instrument.util.StringUtils;
import lombok.Setter;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeData;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.edu_sharing.client.NodeStatistic;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

//@Primary
//@ConditionalOnProperty(prefix = "transaction", name = "tracker", havingValue = "default", matchIfMissing = true)
public class DefaultTransactionTracker extends TransactionTrackerBase {

    @Setter
    private List<String> globalTypeFilter;

    @Setter
    private String workspaceTypes;

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
        List<NodeMetadata> nodeData = new ArrayList<>();
        for (List<Node> partition : partitions) {
            threadPool.execute(() -> {
                nodeData.addAll(alfClient.getNodeMetadata(partition));
            });
        }
        if (!threadPool.awaitQuiescence(10, TimeUnit.MINUTES)) {
            logger.error("Fatal error while processing nodes: alfClient.getNodeMetadata");
            throw new RuntimeException("Fatal error while processing nodes");
        }
        logger.info("getNodeMetadata done. partitions: {}",partitions.size());
        indexNodesMetadata(nodeData);
    }

    public void indexNodesMetadata(List<NodeMetadata> nodeData) throws IOException {

        List<NodeData> toIndexNodes = prepareNodes(nodeData);

        // io's, maps
        logger.info("index user nodes size:" + toIndexNodes.size());
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
            logger.info("starting partition " + pIdx);
            List<IOException> ioExceptions = new ArrayList<>();
            List<BulkOperation> operations = new ArrayList<>();
            for (NodeData nodeData : p) {
                threadPool.execute(() -> {
                    try{
                        workspaceService.addBulkOperation(nodeData, operations);
                    }catch (IOException e){
                        logger.error(e.getMessage(), e);
                        ioExceptions.add(e);
                    }

                });
            }
            if (!threadPool.awaitQuiescence(10, TimeUnit.MINUTES)) {
                logger.error("Fatal error while processing nodes: timeout of prepare bulk operations for metadata index");
                logger.error(p.stream().map(n -> n.getNodeMetadata().getNodeRef()).collect(Collectors.joining(", ")));
            }
            if(!ioExceptions.isEmpty()){
                throw ioExceptions.get(0);
            }
            workspaceService.index(operations);
            logger.info("finished partition " + pIdx);
            pIdx++;
        }
    }

    private List<NodeData> prepareNodes(List<NodeMetadata> nodeData) throws IOException {
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
                    logger.info("FOUND PARENT IO WITH " + parentDbid);
                    //check if exists in list
                    if (nodeData.stream().noneMatch(n -> n.getId() == parentDbid)) {
                        Node n = new Node();
                        n.setId(parentDbid);
                        ioSubobjectChange.add(n);
                    }
                }//else io does not exist in index
            }


            if(!isAllowedType(data)){
                logger.debug("ignoring type:" + data.getType());
                continue;
            }

            toIndexMd.add(data);
        }

        if (!ioSubobjectChange.isEmpty()) {
            toIndexMd.addAll(alfClient.getNodeMetadata(ioSubobjectChange));
        }

        List<NodeData> toIndex = alfClient.getNodeData(toIndexMd);
        for (NodeData data : toIndex) {
            if (data.getNodeMetadata().getNodeRef().startsWith(CCConstants.ARCHIVE_STOREREF)) {
                //skipping preview and valuespace translation for archived nodes
                continue;
            }
            threadPool.execute(() -> {
                eduSharingClient.translateValuespaceProps(data);
            });
        }

        if (!threadPool.awaitQuiescence(10, TimeUnit.MINUTES)) {
            logger.error("Fatal error while processing nodes: timeout of preview and transform processing");
            logger.error(nodeData.stream().map(NodeMetadata::getNodeRef).collect(Collectors.joining(", ")));
        }
        return toIndex;
    }
    public List<NodeMetadata> filterByNodeTypes(List<NodeMetadata> nodeData, String... types ) {
        return nodeData.stream().filter(n -> Arrays.asList(types).contains(n.getType())).collect(Collectors.toList());
    }

    public boolean isAllowedType(NodeMetadata nodeMetadata) {
        if (StringUtils.isNotBlank(workspaceTypes)) {
            String[] allowedTypesArray = workspaceTypes.split(",");
            String type = nodeMetadata.getType();

            return Arrays.asList(allowedTypesArray).contains(type);
        }
        return true;
    }
}
