package org.edu_sharing.elasticsearch.elasticsearch.config.mode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceFactory;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.tracker.TrackerServiceFactory;
import org.edu_sharing.elasticsearch.tracker.TransactionTracker;
import org.edu_sharing.elasticsearch.tracker.strategy.MaxCommitTimeStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "transaction", name="tracker", havingValue = "fix-missing")
public class FixMissingTrackerConfiguration {

    @Bean
    public IndexConfiguration fixMissing(){
        return new IndexConfiguration(req -> req.index("fix-missing"));
    }

    @Bean
    public StatusIndexService<Tx> fixMissingStateService(StatusIndexServiceFactory trackerStateServiceFactory, IndexConfiguration fixMissing) {
        return trackerStateServiceFactory.createTransactionStateService(fixMissing.getIndex());
    }

    @Bean
    public TransactionTracker transactionTracker(TrackerServiceFactory trackerServiceFactory, StatusIndexService<Tx> transactionStateService, StatusIndexService<Tx> fixMissingStateService) {
        try {
            return trackerServiceFactory.createDefaultTrackerService(fixMissingStateService, new MaxCommitTimeStrategy(transactionStateService.getState().getTxnCommitTime()));
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }
}
