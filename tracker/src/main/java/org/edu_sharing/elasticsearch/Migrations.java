package org.edu_sharing.elasticsearch;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationCallback10_1;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationInfo;
import org.edu_sharing.elasticsearch.tracker.auth.AuthoritiesTracker;
import org.edu_sharing.elasticsearch.tracker.main.MainTracker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.Set;

@Configuration
@RequiredArgsConstructor
public class Migrations {

    @Bean
    @Order(0)
    public MigrationInfo migration9_0() {
        return new MigrationInfo("9.0", Set.of(MainTracker.class), null);
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
        return new MigrationInfo("10.0", Set.of(MainTracker.class, AuthoritiesTracker.class), null);
    }

    @Bean
    @Order(3)
    public MigrationInfo migration10_1(ObjectProvider<MigrationCallback10_1> migrationCallback) {
        // TODo document why we need to use  MigrationCallback10_1 via ObjectProvider
        return new MigrationInfo("10.1", Set.of(), migrationCallback::getObject);
    }
}
