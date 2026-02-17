package org.edu_sharing.elasticsearch.tracker.statistics;

import jakarta.ws.rs.client.ResponseProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.edu_sharing.client.EduSharingClient;
import org.edu_sharing.elasticsearch.edu_sharing.client.NodeStatistic;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.StatisticTimestamp;
import org.edu_sharing.elasticsearch.tracker.core.AbstractTracker;
import org.edu_sharing.elasticsearch.tracker.core.TrackingContext;
import org.edu_sharing.elasticsearch.tracker.core.config.BaseTrackerProperties;
import org.edu_sharing.elasticsearch.tracker.strategy.DependentStatusIndexServiceStrategie;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.edu_sharing.elasticsearch.metric.MetricContextHolder.MetricContext.PROGRESS_FACTOR;

@Slf4j
@Component
public class StatisticsTracker extends AbstractTracker<BaseTrackerProperties, StatisticTimestamp> {

    @Value("${statistic.historyInDays}")
    long historyInDays;

    private final WorkspaceService elasticService;
    private final EduSharingClient eduSharingClient;


    private Map<Integer, List<Map.Entry<String, List<NodeStatistic>>>> currentChunks = new HashMap<>();

    private final int chunkSize = 1000;
    private long trackTsTo = -1;
    private boolean allNodesInIndex = true;

    public StatisticsTracker(BaseTrackerProperties statisticTrackerProps,
                             WorkspaceService elasticService,
                             EduSharingClient eduSharingClient) {
        super(statisticTrackerProps);
        this.elasticService = elasticService;
        this.eduSharingClient = eduSharingClient;
    }


    public State track(TrackingContext<StatisticTimestamp> trackingContext) {
        try {
            if (currentChunks.isEmpty()) {
                allNodesInIndex = true;

                long trackTs = getTodayMidnight();
                long trackFromTimeFull = trackTs - (historyInDays * 24L * 60L * 60L * 1000L);
                long trackFromTime = trackFromTimeFull;
                StatisticTimestamp statisticTimestamp = trackingContext.statusIndexService().getState();
                if (statisticTimestamp != null) {
                    trackFromTime = statisticTimestamp.getStatisticTimestamp();
                    log.info("starting from last run {}", new Date(trackFromTime));
                } else {
                    if (trackingContext.strategy() instanceof DependentStatusIndexServiceStrategie && trackingContext.strategy().getLimit() == 0) {
                        log.warn("waiting for dependent tracker");
                        return State.FINISHED;
                    }
                    log.info("starting from history {}", new Date(trackFromTime));
                }


                trackTsTo = trackingContext.strategy().getLimit() != null ? trackingContext.strategy().getLimit() : Long.MAX_VALUE;
                Map<String, List<NodeStatistic>> nodeStatistics = new HashMap<>();
                List<String> statistics = eduSharingClient.getStatisticsNodeIds(trackFromTime, trackTsTo);

                if (statistics.isEmpty()) {
                    trackingContext.metricContext().getProgress().set(100 * PROGRESS_FACTOR);
                    trackingContext.metricContext().getTimestamp().set(System.currentTimeMillis());

                    if (trackingContext.strategy().getLimit() != null) {
                        log.info("max transaction limit by strategy reached: {} / {}", new Date(trackFromTime), new Date(trackingContext.strategy().getLimit()));
                    } else {
                        log.info("index is up to date from: {} to: {}", new Date(trackFromTime), new Date(trackTsTo));
                    }
                    log.info("no statistics found");
                    return State.FINISHED;
                }

                log.info("found {} statistic changes", statistics.size());
                for (String nodeId : statistics) {
                    log.debug("track statistics for node {}", nodeId);
                    try {
                        List<NodeStatistic> statisticsForNode = eduSharingClient.getStatisticsForNode(nodeId, trackFromTimeFull);
                        nodeStatistics.put(nodeId, statisticsForNode);
                    } catch (ResponseProcessingException e) {
                        log.warn("Could not parse statistics for node {}", nodeId, e);
                    }
                }

                AtomicInteger counter = new AtomicInteger();

                currentChunks = nodeStatistics.entrySet().stream().collect(Collectors.groupingBy(e -> counter.getAndIncrement() / chunkSize));
                log.info("splitted into {} chunks", currentChunks.size());
            }

            List<Integer> successfullChunks = new ArrayList<>();
            for (Map.Entry<Integer, List<Map.Entry<String, List<NodeStatistic>>>> entry : currentChunks.entrySet()) {
                log.info("current chunk:{} size: {} all chunks:{}", entry.getKey(), entry.getValue().size(), currentChunks.size());
                Map<String, List<NodeStatistic>> nodeStatistics = new HashMap<>();
                for (Map.Entry<String, List<NodeStatistic>> e : entry.getValue()) {
                    nodeStatistics.put(e.getKey(), e.getValue());
                }
                try {
                    allNodesInIndex = allNodesInIndex && elasticService.updateNodeStatistics(nodeStatistics);
                    elasticService.cleanUpNodeStatistics(new ArrayList<>(nodeStatistics.keySet()));
                    successfullChunks.add(entry.getKey());
                } catch (IOException e) {
                    log.error("problems reaching elastic search server", e);
                }
            }

            trackingContext.metricContext().getProgress().set((long) (successfullChunks.size() / currentChunks.size()) * PROGRESS_FACTOR);
            trackingContext.metricContext().getTimestamp().set(System.currentTimeMillis());

            successfullChunks.forEach(c -> currentChunks.remove(c));

            if (currentChunks.isEmpty()) {
                log.info("finished statistics until:{}", new Date(trackTsTo));
                trackingContext.statusIndexService().setState(new StatisticTimestamp(allNodesInIndex, trackTsTo));
            }
            elasticService.refreshWorkspace();
            return State.IN_PROGRESS;

        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return State.EXCEPTION;
        }
    }

    private long getTodayMidnight() {
        Calendar date = Calendar.getInstance();
// reset hour, minutes, seconds and millis
        date.set(Calendar.HOUR_OF_DAY, 0);
        date.set(Calendar.MINUTE, 0);
        date.set(Calendar.SECOND, 0);
        date.set(Calendar.MILLISECOND, 0);
        return date.getTime().getTime();
    }

    @Override
    public Class<StatisticTimestamp> getStatusClass() {
        return StatisticTimestamp.class;
    }
}
