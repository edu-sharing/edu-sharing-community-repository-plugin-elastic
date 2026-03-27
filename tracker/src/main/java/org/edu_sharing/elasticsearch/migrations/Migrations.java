package org.edu_sharing.elasticsearch.migrations;

import co.elastic.clients.elasticsearch._types.Script;
import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.migrations.callbacks.MigrationCallback10_1;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationInfo;
import org.edu_sharing.elasticsearch.tracker.auth.AuthoritiesTracker;
import org.edu_sharing.elasticsearch.tracker.collection.CollectionSyncTracker;
import org.edu_sharing.elasticsearch.tracker.main.MainTracker;
import org.edu_sharing.elasticsearch.tracker.preview.PreviewTracker;
import org.edu_sharing.elasticsearch.tracker.statistics.StatisticsTracker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
@RequiredArgsConstructor
public class Migrations {

    /**
     * Configuration for migration beans.
     * Don't inject other beans directly here, because of circular dependencies.
     * This Bean is injecte by AutoConfigurationTracker!!!
     * Use ObjectProvider instead.
     */

    @Bean
    @Order(0)
    public MigrationInfo migration9_0() {
        return MigrationInfo.builder()
                .version("9.0")
                .migrateTrackerConfig(MainTracker.class)
                .migrateTrackerConfig(CollectionSyncTracker.class)
                .migrateTrackerConfig(PreviewTracker.class)
                .migrateTrackerConfig(StatisticsTracker.class)
                .build();
    }

    @Bean
    @Order(1)
    public MigrationInfo migration9_1() {
        return MigrationInfo.builder()
                .version("9.1")
                .build();
    }

    @Bean
    @Order(2)
    public MigrationInfo migration10_0() {
        // required cause old tracker did not map workflow object correctly
        return MigrationInfo.builder()
                .version("10.0")
                .migrateTrackerConfig(MainTracker.class)
                .migrateTrackerConfig(CollectionSyncTracker.class)
                .migrateTrackerConfig(PreviewTracker.class)
                .migrateTrackerConfig(StatisticsTracker.class)
                .migrateTrackerConfig(AuthoritiesTracker.class)
                .build();
    }

    @Bean
    @Order(3)
    public MigrationInfo migration10_1(ObjectProvider<MigrationCallback10_1> migrationCallback) {
        return MigrationInfo.builder()
                .version("10.1")
                .migrationCallbackProvider(migrationCallback::getObject)
                .build();
    }

    @Bean
    @Order(3)
    public MigrationInfo migration11_0() {
        //language=groovy
        String script = """
                    if (ctx._source.nodeRef != null && ctx._source.nodeRef.id != null) {
                      ctx._id = ctx._source.nodeRef.id
                    }
                    if(ctx._source.join_children == null) {
                      ctx._source.join_children = "node"
                    }
                """;

        return MigrationInfo.builder()
                .version("11.0")
                .workspaceMigrationScript(Script.of(s -> s
                        .lang("painless")
                        .source(script)))
                .build();
    }
}


//
