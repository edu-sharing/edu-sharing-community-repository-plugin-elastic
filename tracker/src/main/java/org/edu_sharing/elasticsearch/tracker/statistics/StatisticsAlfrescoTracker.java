package org.edu_sharing.elasticsearch.tracker.statistics;

import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.core.AbstractAlfTransactionTracker;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.edu_sharing.elasticsearch.tracker.utils.Partition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.NodeData;

import java.io.IOException;
import java.util.*;

@Slf4j
@Component
public class StatisticsAlfrescoTracker extends AbstractAlfTransactionTracker<AlfTransactionTrackerProperties> {

    @Value("${statistic.historyInDays}")
    long historyInDays;

    private final EduSharingService eduSharingService;

    public StatisticsAlfrescoTracker(AlfTransactionTrackerProperties statisticsAlfrescoTrackerProperties,
                                     EduSharingService eduSharingService) {
        super(statisticsAlfrescoTrackerProperties);
        this.eduSharingService = eduSharingService;
    }


    @Override
    public void trackNodes(List<Node> nodes) throws IOException {
        log.info("Statistics Alfresco Tracker - Start {}",nodes.size());

        Collection<List<Node>> partitions = Partition.getPartitions(nodes, props.getFetchSizeAlfresco());
        List<NodeMetadata> nodeData = Collections.synchronizedList(new ArrayList<>());
        this.threadUtil.runThreaded(
                partitions.stream().toList(),
                p -> nodeData.addAll(alfClient.getNodeMetadata(p)),
                true,
                true);
        Map<String, List<NodeData>> updateNodeStatistics = new HashMap<>();
        for (NodeMetadata nodeDataStat : nodeData) {
            String nodeId = Tools.getUUID(nodeDataStat.getNodeRef());
            log.info("track statistics for node {}", nodeId);
            long trackTs = System.currentTimeMillis();
            long trackFromTime = trackTs - (historyInDays * 24L * 60L * 60L * 1000L);
            List<NodeData> statisticsForNode = eduSharingService.getStatisticsForNode(nodeId, trackFromTime);
            updateNodeStatistics.put(nodeId, statisticsForNode);
            //we don't need cleanup cause former elasticClient.index(..) call removes all statistic data
            //elasticClient.cleanUpNodeStatistics(nodeDataStat);
        }
        workspaceService.updateNodeStatistics(updateNodeStatistics);
    }
}
