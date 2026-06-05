package org.edu_sharing.elasticsearch.elasticsearch.config.mode;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.elasticsearch.core.DefaultApplicationState;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.elasticsearch.core.hook.ApplicationStartupHook;
import org.edu_sharing.elasticsearch.elasticsearch.core.hook.ApplicationStartupHookService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.WaitForMigrationJob;
import org.edu_sharing.elasticsearch.tracker.core.TrackerExecutorFactory;
import org.edu_sharing.elasticsearch.tracker.core.TrackerRegistry;
import org.edu_sharing.elasticsearch.tracker.core.TrackerScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mode", havingValue = "default", matchIfMissing = true)
public class DefaultConfiguration {


    @Bean
    public DefaultApplicationState defaultApplicationState() {
        return new DefaultApplicationState();
    }

    @Bean
    public ApplicationStartupHookService applicationStartupHookService(ElasticsearchClient elasticsearchClient, List<ApplicationStartupHook> hooks) {
        return new ApplicationStartupHookService(elasticsearchClient,hooks, defaultApplicationState());
    }

    @Bean
    public WaitForMigrationJob waitForMigrationJob(MigrationService migrationService, DefaultApplicationState defaultApplicationState) {
        return new WaitForMigrationJob(migrationService, defaultApplicationState);
    }


    @Bean
    public TrackerScheduler trackerScheduler(TrackerRegistry trackerRegistry, IndexConfiguration trackerState, TrackerExecutorFactory trackerExecutorFactory) {
        return new TrackerScheduler(trackerState, trackerRegistry, trackerExecutorFactory);
    }
}
