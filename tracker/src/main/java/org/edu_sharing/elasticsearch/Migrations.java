package org.edu_sharing.elasticsearch;

import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationInfo;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

@Configuration
public class Migrations {

    @Bean
    @Order(0)
    public MigrationInfo migration9_0() {
        return new MigrationInfo("9.0", true, false, null);
    }

    @Bean
    @Order(1)
    public MigrationInfo migration9_1() {
        return new MigrationInfo("9.1", false, false, null);
    }

    @Bean
    @Order(2)
    public MigrationInfo migration10_0() {
        /**
         * required cause old tracker did not map workflow object correctly
         */
        return new MigrationInfo("10.0", true,true, null);
    }
}
