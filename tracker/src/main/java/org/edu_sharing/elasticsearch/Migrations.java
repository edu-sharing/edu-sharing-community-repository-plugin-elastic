package org.edu_sharing.elasticsearch;

import co.elastic.clients.elasticsearch._types.Script;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.IndexMigrationInfo;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationInfo;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
public class Migrations {

    @Bean
    @Order(0)
    public MigrationInfo migration9_0() {
        return new MigrationInfo("9.0",
                new IndexMigrationInfo(true, null),
                new IndexMigrationInfo(false, null));
    }

    @Bean
    @Order(1)
    public MigrationInfo migration9_1() {
        return new MigrationInfo("9.1",
                new IndexMigrationInfo(false, null),
                new IndexMigrationInfo(false, null));
    }

    @Bean
    @Order(2)
    public MigrationInfo migration10_0() {
        // required cause old tracker did not map workflow object correctly
        return new MigrationInfo("10.0",
                new IndexMigrationInfo(true, null),
                new IndexMigrationInfo(true, null));
    }

    @Bean
    @Order(3)
    public MigrationInfo migration11_0() {
        // required for userEvents
        return new MigrationInfo("11.0",
                new IndexMigrationInfo(false, Script.of(s -> s
                        .lang("painless")
                        .source("ctx._source.join_children=\"node\""))),
                new IndexMigrationInfo(false, null));
    }
}
