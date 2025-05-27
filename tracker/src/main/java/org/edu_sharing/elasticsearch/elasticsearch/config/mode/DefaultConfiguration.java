package org.edu_sharing.elasticsearch.elasticsearch.config.mode;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.TrackerAvailabilityTickService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.WaitForMigrationJob;
import org.edu_sharing.elasticsearch.jobs.AclTrackerJob;
import org.edu_sharing.elasticsearch.jobs.CascadeTrackerJob;
import org.edu_sharing.elasticsearch.jobs.StatisticsTrackerJob;
import org.edu_sharing.elasticsearch.jobs.TransactionTrackerJob;
import org.edu_sharing.elasticsearch.tracker.AclTracker;
import org.edu_sharing.elasticsearch.tracker.CascadeTracker;
import org.edu_sharing.elasticsearch.tracker.StatisticsTracker;
import org.edu_sharing.elasticsearch.tracker.TransactionTracker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mode", havingValue = "default", matchIfMissing = true)
public class DefaultConfiguration {

    @Bean
    public WaitForMigrationJob waitForMigrationJob(MigrationService migrationService){
        return new WaitForMigrationJob(migrationService);
    }

    @Bean
    public TransactionTrackerJob transactionTrackerJob(TransactionTracker transactionTracker, TrackerAvailabilityTickService tickService){
        return new TransactionTrackerJob(transactionTracker, tickService);
    }

    @Bean
    public AclTrackerJob aclTrackerJob(AclTracker aclTracker, TrackerAvailabilityTickService tickService){
        return new AclTrackerJob(aclTracker, tickService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "statistic", name = "enabled", havingValue = "true")
    public StatisticsTrackerJob statisticsTrackerJob(StatisticsTracker statisticsTracker, TrackerAvailabilityTickService tickService){
        return new StatisticsTrackerJob(statisticsTracker, tickService);
    }

    @Bean
    public CascadeTrackerJob cascadeTrackerJob(CascadeTracker cascadeTracker){
        return new CascadeTrackerJob(cascadeTracker);
    }
}
