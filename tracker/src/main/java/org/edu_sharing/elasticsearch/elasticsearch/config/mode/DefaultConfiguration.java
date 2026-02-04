package org.edu_sharing.elasticsearch.elasticsearch.config.mode;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.TrackerAvailabilityTickService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.WaitForMigrationJob;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.RelationTx;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.UserActivityTx;
import org.edu_sharing.elasticsearch.jobs.*;
import org.edu_sharing.elasticsearch.tracker.*;
import org.edu_sharing.elasticsearch.tracker.generic.GenericTimebaseTracker;
import org.edu_sharing.elasticsearch.tracker.generic.GenericTimebaseTrackerFactory;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.RelationData;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.UserNodeActivity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mode", havingValue = "default", matchIfMissing = true)
public class DefaultConfiguration {
    private final GenericTimebaseTrackerFactory genericTimebaseTrackerFactory;


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
    @ConditionalOnProperty(prefix = "userActivities", name = "enabled", havingValue = "true")
    public UserActivityTrackerJob userActivitiesTrackerJob(UserActivityTrackerSupportFactory userActivityTrackerSupportFactory, StatusIndexService<UserActivityTx> userActivityTxStatusIndexService, TrackerAvailabilityTickService tickService){
        GenericTimebaseTracker<UserNodeActivity, UserActivityTx> userActivityTracker = genericTimebaseTrackerFactory.createTracker(userActivityTxStatusIndexService);
        userActivityTracker.addTrackingSupport(userActivityTrackerSupportFactory.getTrackingSupport());
        return new UserActivityTrackerJob(userActivityTracker, tickService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "relations", name = "enabled", havingValue = "true")
    public RelationDataTrackerJob relationDataTrackerJob(RelationDataTrackingSupportFactory relationDataTrackingSupportFactory, StatusIndexService<RelationTx> relationTxStatusIndexService, TrackerAvailabilityTickService tickService){
        GenericTimebaseTracker<RelationData, RelationTx> relationDataTracker = genericTimebaseTrackerFactory.createTracker(relationTxStatusIndexService);
        relationDataTracker.addTrackingSupport(relationDataTrackingSupportFactory.getTrackingSupport());
        return new RelationDataTrackerJob(relationDataTracker, tickService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "shares", name = "enabled", havingValue = "true")
    public ShareTrackerJob shareTrackerJob(ShareInfoTracker shareInfoTracker, TrackerAvailabilityTickService tickService){
        return new ShareTrackerJob(shareInfoTracker, tickService);
    }

    @Bean
    public CascadeTrackerJob cascadeTrackerJob(CascadeTracker cascadeTracker){
        return new CascadeTrackerJob(cascadeTracker);
    }
}
