package org.edu_sharing.elasticsearch.tracker;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.alfresco.client.AlfrescoWebscriptClient;
import org.edu_sharing.elasticsearch.edu_sharing.client.EduSharingClient;
import org.edu_sharing.elasticsearch.elasticsearch.core.AuthorityService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.metric.MetricContextHolder;
import org.edu_sharing.elasticsearch.tracker.strategy.FixNumberOfTransactionStrategy;
import org.edu_sharing.elasticsearch.tracker.strategy.TrackerStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class TrackerServiceFactory {

    private final AlfrescoWebscriptClient alfClient;
    private final WorkspaceService workspaceService;
    private final AuthorityService authorityService;
    private final EduSharingClient eduSharingClient;

    @Value("${threading.threadCount}")
    Integer threadCount;

    @Value("${transactions.max:500}")
    int numberOfTransactions;

    @Value("${statistic.historyInDays}")
    private long historyInDays;

    @Value("${index.storerefs}")
    private List<String> indexStoreRefs;

    @Value("#{'${include.nodeTypes:}'.isEmpty() ? {} : '${include.nodeTypes}'.split(',')}")
    private List<String> includeNodeTypes;

    @Value("#{'${exclude.nodeTypes:}'.isEmpty() ? {} : '${exclude.nodeTypes}'.split(',')}")
    private List<String> excludeNodeTypes;

    @Value("${allowed.types}")
    private String allowedTypes;

    @Value("${tracker.fetch.size.alfresco}")
    private  int fetchSizeAlfresco;

    @Value("${tracker.bulk.size.elastic}")
    private  int bulkSizeElastic;

    public DefaultTransactionTracker createDefaultTrackerService(StatusIndexService<Tx> transactionStateService) {
        return createDefaultTrackerService(transactionStateService, new FixNumberOfTransactionStrategy());
    }
    public DefaultTransactionTracker createDefaultTrackerService(StatusIndexService<Tx> transactionStateService, TrackerStrategy trackerStrategy) {
        return createTrackerService(DefaultTransactionTracker::new, transactionStateService, trackerStrategy, MetricContextHolder.getTransactionContext());
    }

    public <T extends TransactionTrackerBase> T createTrackerService(Supplier<T> trackerSupplier, StatusIndexService<Tx> transactionStateService, TrackerStrategy trackerStrategy, MetricContextHolder.MetricContext metricContext){
        T defaultTransactionTracker = trackerSupplier.get();
        defaultTransactionTracker.setAlfClient(alfClient);
        defaultTransactionTracker.setWorkspaceService(workspaceService);
        defaultTransactionTracker.setAuthorityService(authorityService);
        defaultTransactionTracker.setEduSharingClient(eduSharingClient);
        defaultTransactionTracker.setTransactionStateService(transactionStateService);
        defaultTransactionTracker.setTrackerStrategy(trackerStrategy);

        defaultTransactionTracker.setNumberOfTransactions(numberOfTransactions);
        defaultTransactionTracker.setThreadCount(threadCount);
        defaultTransactionTracker.setMetricContext(metricContext);
        if(defaultTransactionTracker instanceof  DefaultTransactionTracker dtt){
            dtt.setIndexStoreRefs(indexStoreRefs);
            dtt.setWorkspaceTypes(allowedTypes);
            dtt.setHistoryInDays(historyInDays);
            dtt.setFetchSizeAlfresco(fetchSizeAlfresco);
            dtt.setBulkSizeElastic(bulkSizeElastic);
        }
        defaultTransactionTracker.setExcludeNodeTypes(excludeNodeTypes);
        defaultTransactionTracker.setIncludeNodeTypes(includeNodeTypes);
        defaultTransactionTracker.init();
        return defaultTransactionTracker;
    }


}
