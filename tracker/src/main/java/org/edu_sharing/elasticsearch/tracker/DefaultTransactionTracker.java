package org.edu_sharing.elasticsearch.tracker;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.*;
import org.edu_sharing.elasticsearch.elasticsearch.core.types.ReindexParentConfig;
import org.edu_sharing.elasticsearch.elasticsearch.core.types.TypesConfig;
import org.edu_sharing.elasticsearch.elasticsearch.core.types.TypesConfigItem;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class DefaultTransactionTracker extends TransactionTrackerBase {

    @Setter
    protected List<String> indexStoreRefs;


    @Setter
    private long historyInDays;

    @Setter
    @Value("${tracker.fetch.size.alfresco}")
    int fetchSizeAlfresco;

    @Setter
    @Value("${statistic.enabled}")
    boolean statisticEnabled;

    @Setter
    @Value("${tracker.bulk.size.elastic}")
    int bulkSizeElastic;

    @Setter(onMethod_ = @Autowired)
    private TypesConfig typesConfig;


    public DefaultTransactionTracker() {
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
        authorityService.delete(toDelete);


        // index nodes
        //some transactions can have a lot of Nodes which can cause trouble on alfresco so use partitioning
        Collection<List<Node>> partitions = Partition.getPartitions(nodes, fetchSizeAlfresco);

        int pIdx = 0;
        for (List<Node> partition : partitions) {
            log.info("indexNodes partition " + pIdx);
            indexNodes(partition);
            pIdx++;
        }
    }

    public void indexNodes(List<Node> nodes) throws IOException {
        log.info("getNodeMetadata start " + nodes.size());
        List<NodeMetadata> nodeData = alfClient.getNodeMetadata(nodes);
        log.info("getNodeMetadata done " + nodeData.size());
        indexNodesMetadata(nodeData);
    }

    public void indexNodesMetadata(List<NodeMetadata> nodeData) throws IOException {

        List<NodeData> toIndexNodes = prepareNodes(nodeData);

        // io's, maps
        log.info("index user nodes size:" + toIndexNodes.size());
        updateNodes(toIndexNodes);
        if (statisticEnabled) {
            updateNodeStatistics(toIndexNodes);
        }
        // refresh index so that collections will be found by cacheCollections process
        workspaceService.refreshWorkspace();


        // usages, proposals
        List<NodeMetadata> toIndexUsagesProposalsMd = filterByNodeTypes(nodeData, "ccm:usage", "ccm:collection_proposal");
        log.info("index usages/proposal size:" + toIndexUsagesProposalsMd.size());
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
        Map<String, List<org.edu_sharing.generated.repository.backend.services.rest.client.model.NodeData>> updateNodeStatistics = new HashMap<>();
        for (NodeData nodeDataStat : toIndex) {
            if (!"ccm:io".equals(nodeDataStat.getNodeMetadata().getType()) || !Tools.getProtocol(nodeDataStat.getNodeMetadata().getNodeRef()).equals("workspace")) {
                continue;
            }

            long trackTs = System.currentTimeMillis();
            long trackFromTime = trackTs - (historyInDays * 24L * 60L * 60L * 1000L);
            String nodeId = Tools.getUUID(nodeDataStat.getNodeMetadata().getNodeRef());
            List<org.edu_sharing.generated.repository.backend.services.rest.client.model.NodeData> statisticsForNode = eduSharingService.getStatisticsForNode(nodeId, trackFromTime);
            updateNodeStatistics.put(nodeId, statisticsForNode);
            //we don't need cleanup cause former elasticClient.index(..) call removes all statistic data
            //elasticClient.cleanUpNodeStatistics(nodeDataStat);
        }
        workspaceService.updateNodeStatistics(updateNodeStatistics);
    }

    private void updateNodes(List<NodeData> toIndex) throws IOException {
        Collection<List<NodeData>> partitioned = Partition.getPartitions(toIndex, bulkSizeElastic);
        for (List<NodeData> p : partitioned) {
            workspaceService.index(p);
        }
    }

    protected List<NodeData> prepareAuthorities(List<NodeMetadata> nodeMetadata) {
        List<NodeMetadata> toIndexAuthorities = filterByNodeTypes(nodeMetadata, "cm:person", "cm:authorityContainer");
        return alfClient.getNodeData(toIndexAuthorities);
    }

    private List<NodeData> prepareNodes(List<NodeMetadata> nodeData) throws IOException {
        List<NodeMetadata> toIndexMd = new ArrayList<>();
        List<Node> ioSubobjectChange = new ArrayList<>();

        for (NodeMetadata data : nodeData) {

            TypesConfigItem typeConfig = typesConfig.getTypeConfig(data.getType());
            ReindexParentConfig reindexParentConfig = typeConfig.reindexParent();

            //force reindex of parent io to get subobjects
            if (reindexParentConfig.enabled()
                    && CCConstants.STORE_WORKSPACES_SPACES.equals(Tools.getStoreRef(data.getNodeRef()))
                    && reindexParentConfig.filter().match(data)) {

                List<String> paths = Arrays.stream(data.getPaths().get(0).getApath().split("/")).collect(Collectors.toList());
                Collections.reverse(paths);
                List<String> parentIds = paths.stream().limit(reindexParentConfig.maxLookAHead()).map(x -> CCConstants.STORE_WORKSPACES_SPACES + "/" + x).toList();
                Map<String, Serializable> values = workspaceService.getProperty(parentIds, "dbid");

                ioSubobjectChange.addAll(values.values().stream()
                        .map(serializable -> (Number) serializable)
                        .map(Number::longValue)
                        .map(x->Node.builder().id(x).build())
                        .toList());
            }

            if (!typeConfig.index()) {
                log.debug("ignoring type: {}", data.getType());
                continue;
            }

            toIndexMd.add(data);
        }

        if (!ioSubobjectChange.isEmpty()) {
            // we need to recursively go up to get the root parent
            toIndexMd.addAll(alfClient.getNodeMetadata(ioSubobjectChange));
        }

        List<NodeData> toIndex = alfClient.getNodeData(toIndexMd);
        for (NodeData data : toIndex) {
            if (data.getNodeMetadata().getNodeRef().startsWith(CCConstants.ARCHIVE_STOREREF)) {
                //skipping preview and valuespace translation for archived nodes
                continue;
            }
            threadPool.execute(() -> {
                eduSharingService.addPreview(data);
                eduSharingService.translateValuespaceProps(data);
            });
        }

        if (!threadPool.awaitQuiescence(10, TimeUnit.MINUTES)) {
            log.error("Fatal error while processing nodes: timeout of preview and transform processing");
            log.error(nodeData.stream().map(NodeMetadata::getNodeRef).collect(Collectors.joining(", ")));
        }
        return toIndex;
    }

    public List<NodeMetadata> filterByNodeTypes(List<NodeMetadata> nodeData, String... types) {
        return nodeData.stream().filter(n -> Arrays.asList(types).contains(n.getType())).collect(Collectors.toList());
    }

    public boolean isAllowedType(NodeMetadata nodeMetadata) {
        TypesConfigItem typeConfig = typesConfig.getTypeConfig(nodeMetadata.getType());
        return typeConfig.index();
    }
}

