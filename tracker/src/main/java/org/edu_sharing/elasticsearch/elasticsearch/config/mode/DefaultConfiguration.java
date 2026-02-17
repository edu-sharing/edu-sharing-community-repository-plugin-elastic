package org.edu_sharing.elasticsearch.elasticsearch.config.mode;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.WaitForMigrationJob;
import org.edu_sharing.elasticsearch.tracker.core.TrackerExecutorFactory;
import org.edu_sharing.elasticsearch.tracker.core.TrackerRegistry;
import org.edu_sharing.elasticsearch.tracker.core.TrackerScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "mode", havingValue = "default", matchIfMissing = true)
public class DefaultConfiguration {

    @Bean
    public WaitForMigrationJob waitForMigrationJob(MigrationService migrationService) {
        return new WaitForMigrationJob(migrationService);
    }

    @Bean
    public TrackerScheduler trackerScheduler(TrackerRegistry trackerRegistry, IndexConfiguration trackerState, TrackerExecutorFactory trackerExecutorFactory) {
        return new TrackerScheduler(trackerState, trackerRegistry, trackerExecutorFactory);
    }
}
