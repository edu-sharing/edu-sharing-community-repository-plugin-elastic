package org.edu_sharing.elasticsearch.tracker;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.alfresco.client.AlfrescoWebscriptClient;
import org.edu_sharing.elasticsearch.edu_sharing.client.EduSharingClient;
import org.edu_sharing.elasticsearch.elasticsearch.core.AuthorityService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.tracker.strategy.FixNumberOfTransactionStrategy;
import org.edu_sharing.elasticsearch.tracker.strategy.TrackerStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

    @Value("${statistic.historyInDays}")
    private long historyInDays;

    @Value("${index.storerefs}")
    private List<String> indexStoreRefs;

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
        DefaultTransactionTracker defaultTransactionTracker = new DefaultTransactionTracker(alfClient, workspaceService, authorityService, eduSharingClient, transactionStateService, trackerStrategy);
        defaultTransactionTracker.setNumberOfTransactions(numberOfTransactions);
        defaultTransactionTracker.setThreadCount(threadCount);
        defaultTransactionTracker.setIndexStoreRefs(indexStoreRefs);
        defaultTransactionTracker.setAllowedTypes(allowedTypes);
        defaultTransactionTracker.setHistoryInDays(historyInDays);
        defaultTransactionTracker.setFetchSizeAlfresco(fetchSizeAlfresco);
        defaultTransactionTracker.setBulkSizeElastic(bulkSizeElastic);
        defaultTransactionTracker.init();


        return defaultTransactionTracker;
    }


}
