package org.edu_sharing.elasticsearch.tracker;

import io.micrometer.core.instrument.util.StringUtils;
import lombok.Setter;
import org.edu_sharing.elasticsearch.alfresco.client.*;
import org.edu_sharing.elasticsearch.edu_sharing.client.EduSharingClient;
import org.edu_sharing.elasticsearch.edu_sharing.client.NodeStatistic;
import org.edu_sharing.elasticsearch.elasticsearch.core.AuthorityService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.strategy.TrackerStrategy;
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
    private String allowedTypes;

    @Setter
    private List<String> indexStoreRefs;
    private final List<String> subTypes = Arrays.asList("ccm:io", "ccm:rating", "ccm:comment", "ccm:usage", "ccm:collection_proposal");


    private final Logger logger = LoggerFactory.getLogger(DefaultTransactionTracker.class);

    @Setter
    private long historyInDays;

    @Setter
    @Value("${tracker.fetch.size.alfresco}")
    int fetchSizeAlfresco;

    @Setter
    @Value("${tracker.bulk.size.elastic}")
    int bulkSizeElastic;

    public DefaultTransactionTracker(AlfrescoWebscriptClient alfClient, WorkspaceService workspaceService, AuthorityService authorityService, EduSharingClient eduSharingClient, StatusIndexService<Tx> transactionStateService, TrackerStrategy strategy) {
        super(alfClient, eduSharingClient, workspaceService, authorityService, transactionStateService, strategy);
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

        int pIdx = 0;
        for (List<Node> partition : partitions) {
            logger.info("indexNodes partition " + pIdx);
            indexNodes(partition);
            pIdx++;
        }
    }

    public void indexNodes(List<Node> nodes) throws IOException {
        logger.info("getNodeMetadata start " + nodes.size());
        List<NodeMetadata> nodeData = alfClient.getNodeMetadata(nodes);
        logger.info("getNodeMetadata done " + nodeData.size());
        indexNodesMetadata(nodeData);
    }

    public void indexNodesMetadata(List<NodeMetadata> nodeData) throws IOException {

        List<NodeData> toIndexNodes = prepareNodes(nodeData);

        // io's, maps
        logger.info("index user nodes size:" + toIndexNodes.size());
        updateNodes(toIndexNodes);
        updateNodeStatistics(toIndexNodes);

        // refresh index so that collections will be found by cacheCollections process
        workspaceService.refreshWorkspace();


        // usages, proposals
        List<NodeMetadata> toIndexUsagesProposalsMd = filterByNodeTypes(nodeData,"ccm:usage", "ccm:collection_proposal");
        logger.info("index usages/proposal size:" + toIndexUsagesProposalsMd.size());
        updateUsageProposals(toIndexUsagesProposalsMd);

        // authorities
        List<NodeData> toIndexAuthorities = prepareAuthorities(nodeData);
        authorityService.index(toIndexAuthorities);

    }

    private void updateUsageProposals(List<NodeMetadata> toIndexUsagesProposalsMd) throws IOException {
        for (NodeMetadata usage : toIndexUsagesProposalsMd) {
            workspaceService.indexCollections(usage);
        }
    }

    private void updateNodeStatistics(List<NodeData> toIndex) throws IOException {
        for (NodeData nodeDataStat : toIndex) {
            if (!"ccm:io".equals(nodeDataStat.getNodeMetadata().getType()) || !Tools.getProtocol(nodeDataStat.getNodeMetadata().getNodeRef()).equals("workspace")) {
                continue;
            }

            long trackTs = System.currentTimeMillis();
            long trackFromTime = trackTs - (historyInDays * 24L * 60L * 60L * 1000L);
            String nodeId = Tools.getUUID(nodeDataStat.getNodeMetadata().getNodeRef());
            List<NodeStatistic> statisticsForNode = eduSharingClient.getStatisticsForNode(nodeId, trackFromTime);
            Map<String, List<NodeStatistic>> updateNodeStatistics = new HashMap<>();
            updateNodeStatistics.put(nodeId, statisticsForNode);
            workspaceService.updateNodeStatistics(updateNodeStatistics);
            //we don't need cleanup cause former elasticClient.index(..) call removes all statistic data
            //elasticClient.cleanUpNodeStatistics(nodeDataStat);
        }
    }

    private void updateNodes(List<NodeData> toIndex) throws IOException {
        Collection<List<NodeData>> partitioned = Partition.getPartitions(toIndex, bulkSizeElastic);
        for (List<NodeData> p : partitioned) {
            workspaceService.index(p);
        }
    }

    private List<NodeData> prepareAuthorities(List<NodeMetadata> nodeMetadata){
        List<NodeMetadata> toIndexAuthorities = filterByNodeTypes(nodeMetadata,"cm:person","cm:authorityContainer");
        List<NodeData> toIndex = alfClient.getNodeData(toIndexAuthorities);
        return toIndex;
    }

    private List<NodeData> prepareNodes(List<NodeMetadata> nodeData) throws IOException {
        List<NodeMetadata> toIndexMd = new ArrayList<>();
        List<Node> ioSubobjectChange = new ArrayList<>();

        for (NodeMetadata data : nodeData) {

            //force reindex of parent io to get subobjects
            if (subTypes.contains(data.getType())
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

            if (StringUtils.isNotBlank(allowedTypes)) {
                String[] allowedTypesArray = allowedTypes.split(",");
                String type = data.getType();

                if (!Arrays.asList(allowedTypesArray).contains(type)) {
                    logger.debug("ignoring type:" + type);
                    continue;
                }
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
                eduSharingClient.addPreview(data);
                eduSharingClient.translateValuespaceProps(data);
            });
        }

        if (!threadPool.awaitQuiescence(10, TimeUnit.MINUTES)) {
            logger.error("Fatal error while processing nodes: timeout of preview and transform processing");
            logger.error(nodeData.stream().map(NodeMetadata::getNodeRef).collect(Collectors.joining(", ")));
        }
        return toIndex;
    }

    private List<NodeMetadata> filterByNodeTypes(List<NodeMetadata> nodeData, String... types ){
        return nodeData.stream().filter(n ->  Arrays.asList(types).contains(n.getType())).collect(Collectors.toList());
    }

    public boolean isAllowedType(NodeMetadata nodeMetadata) {
        if (StringUtils.isNotBlank(allowedTypes)) {
            String[] allowedTypesArray = allowedTypes.split(",");
            String type = nodeMetadata.getType();

            return Arrays.asList(allowedTypesArray).contains(type);
        }
        return true;
    }
}
