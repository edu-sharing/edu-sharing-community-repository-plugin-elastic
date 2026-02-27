package org.edu_sharing.elasticsearch.migrations;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationCallback;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationContext;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationException;
import org.edu_sharing.elasticsearch.migrations.callbacks.MigrationCallback10_1;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationInfo;
import org.edu_sharing.elasticsearch.tracker.auth.AuthoritiesTracker;
import org.edu_sharing.elasticsearch.tracker.collection.CollectionSyncTracker;
import org.edu_sharing.elasticsearch.tracker.main.MainTracker;
import org.edu_sharing.elasticsearch.tracker.preview.PreviewTracker;
import org.edu_sharing.elasticsearch.tracker.statistics.StatisticsTracker;
import org.edu_sharing.elasticsearch.tracker.usage.UsageSyncTracker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.util.Set;

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
        return new MigrationInfo("9.0",
                Set.of(
                        MainTracker.class,
                        CollectionSyncTracker.class,
                        PreviewTracker.class,
                        StatisticsTracker.class,
                        UsageSyncTracker.class
                ), null);
    }

    @Bean
    @Order(1)
    public MigrationInfo migration9_1() {
        return new MigrationInfo("9.1", Set.of(), null);
    }

    @Bean
    @Order(2)
    public MigrationInfo migration10_0() {
        // required cause old tracker did not map workflow object correctly
        return new MigrationInfo("10.0",
                Set.of(
                        MainTracker.class,
                        CollectionSyncTracker.class,
                        PreviewTracker.class,
                        StatisticsTracker.class,
                        UsageSyncTracker.class,
                        AuthoritiesTracker.class
                ), null);
    }

    @Bean
    @Order(3)
    public MigrationInfo migration10_1(ObjectProvider<MigrationCallback10_1> migrationCallback) {
        return new MigrationInfo("10.1", Set.of(), migrationCallback::getObject);
    }

    @Bean
    @Order(3)
    public MigrationInfo migration11_0() {
        return new MigrationInfo("11.0", Set.of(), () -> new MigrationCallback() {
            @Override
            public String getName() {
                return "MigrationCallback11_0";
            }

            @Override
            public void onMigrationCallback(MigrationContext context, ElasticsearchClient client) {
                try {
                    client.scriptsPainlessExecute(req -> req.script(s -> s
                            .lang("painless")
                            .source(//language=groovy
                                    """
                                    if (ctx._source.nodeRef != null && ctx._source.nodeRef.id != null) {
                                      ctx._id = ctx._source.nodeRef.id
                                    }
                                    if(ctx._source.join_children == null) {
                                      ctx._source.join_children = "node"
                                    }
                                    """)));
                } catch (IOException e) {
                    throw new MigrationException(String.format("Failed to execute painless script: %s", e.getMessage()));
                }
            }
        });
    }
}


//
