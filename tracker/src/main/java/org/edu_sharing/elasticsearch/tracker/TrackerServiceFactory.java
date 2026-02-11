package org.edu_sharing.elasticsearch.tracker;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.alfresco.client.AlfrescoWebscriptClient;
import org.edu_sharing.elasticsearch.edu_sharing.client.EduSharingClient;
import org.edu_sharing.elasticsearch.elasticsearch.core.AuthorityService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.metric.MetricContextHolder;
import org.edu_sharing.elasticsearch.tracker.config.TrackerProvider;
import org.edu_sharing.elasticsearch.tracker.strategy.FixNumberOfTransactionStrategy;
import org.edu_sharing.elasticsearch.tracker.strategy.TrackerStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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

    @Value("${index.storerefs}")
    private List<String> indexStoreRefs;

    @Value("${include.nodeTypes}")
    private List<String> includeNodeTypes;

    @Value("${exclude.nodeTypes}")
    private List<String> excludeNodeTypes;

    @Value("${allowed.types}")
    private List<String> allowedTypes;

    @Value("${tracker.fetch.size.alfresco}")
    private int fetchSizeAlfresco;

    @Value("${tracker.bulk.size.elastic}")
    private int bulkSizeElastic;

    public DefaultTransactionTracker createDefaultTrackerService(StatusIndexService<Tx> transactionStateService) {
        return createDefaultTrackerService(transactionStateService, new FixNumberOfTransactionStrategy());
    }

    public DefaultTransactionTracker createDefaultTrackerService(StatusIndexService<Tx> transactionStateService, TrackerStrategy trackerStrategy) {
        return createTrackerService(DefaultTransactionTracker::new, transactionStateService, trackerStrategy, MetricContextHolder.getTransactionContext());
    }

    public <T extends TransactionTrackerBase> T createTrackerService(TrackerProvider<T> trackerProvider, StatusIndexService<Tx> transactionStateService, TrackerStrategy trackerStrategy, MetricContextHolder.MetricContext metricContext) {
        T transactionTracker = trackerProvider.create();
        transactionTracker.setAlfClient(alfClient);
        transactionTracker.setWorkspaceService(workspaceService);
        transactionTracker.setAuthorityService(authorityService);
        transactionTracker.setEduSharingClient(eduSharingClient);
        transactionTracker.setTransactionStateService(transactionStateService);
        transactionTracker.setTrackerStrategy(trackerStrategy);

        transactionTracker.setThreadUtil(new ThreadUtil(threadCount));
        transactionTracker.setMetricContext(metricContext);
        if (transactionTracker instanceof DefaultTransactionTracker dtt) {
            dtt.setIndexStoreRefs(indexStoreRefs);
            dtt.setWorkspaceTypes(allowedTypes);
            dtt.setFetchSizeAlfresco(fetchSizeAlfresco);
            dtt.setBulkSizeElastic(bulkSizeElastic);
        }
        transactionTracker.setNumberOfTransactions(numberOfTransactions);
        transactionTracker.setExcludeNodeTypes(new ArrayList<>(excludeNodeTypes));
        transactionTracker.setIncludeNodeTypes(new ArrayList<>(includeNodeTypes));
        transactionTracker.init();
        return transactionTracker;
    }


}
