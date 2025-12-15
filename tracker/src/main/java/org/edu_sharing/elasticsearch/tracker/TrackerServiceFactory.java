package org.edu_sharing.elasticsearch.tracker;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.alfresco.client.AlfrescoWebscriptClient;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.AuthorityService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
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
    private final EduSharingService eduSharingService;

    @Value("${threading.threadCount}")
    Integer threadCount;

    @Value("${transactions.max:500}")
    int numberOfTransactions;

    @Value("${statistic.historyInDays}")
    private long historyInDays;

    @Value("${index.storerefs}")
    private List<String> indexStoreRefs;

    @Value("${tracker.fetch.size.alfresco}")
    private  int fetchSizeAlfresco;

    @Value("${tracker.bulk.size.elastic}")
    private  int bulkSizeElastic;

    public DefaultTransactionTracker createDefaultTrackerService(StatusIndexService<Tx> transactionStateService) {
        return createDefaultTrackerService(transactionStateService, new FixNumberOfTransactionStrategy());
    }
    public DefaultTransactionTracker createDefaultTrackerService(StatusIndexService<Tx> transactionStateService, TrackerStrategy trackerStrategy) {
        return createTrackerService(DefaultTransactionTracker::new, transactionStateService, trackerStrategy);
    }

    public <T extends DefaultTransactionTracker> T createTrackerService(Supplier<T> trackerSupplier, StatusIndexService<Tx> transactionStateService, TrackerStrategy trackerStrategy){
        T defaultTransactionTracker = trackerSupplier.get();
        defaultTransactionTracker.setAlfClient(alfClient);
        defaultTransactionTracker.setWorkspaceService(workspaceService);
        defaultTransactionTracker.setAuthorityService(authorityService);
        defaultTransactionTracker.setEduSharingService(eduSharingService);
        defaultTransactionTracker.setTransactionStateService(transactionStateService);
        defaultTransactionTracker.setTrackerStrategy(trackerStrategy);

        defaultTransactionTracker.setNumberOfTransactions(numberOfTransactions);
        defaultTransactionTracker.setThreadCount(threadCount);
        defaultTransactionTracker.setIndexStoreRefs(indexStoreRefs);
        defaultTransactionTracker.setHistoryInDays(historyInDays);
        defaultTransactionTracker.setFetchSizeAlfresco(fetchSizeAlfresco);
        defaultTransactionTracker.setBulkSizeElastic(bulkSizeElastic);
        defaultTransactionTracker.init();
        return defaultTransactionTracker;
    }


}
