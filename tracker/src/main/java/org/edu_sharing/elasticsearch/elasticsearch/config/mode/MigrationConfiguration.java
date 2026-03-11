package org.edu_sharing.elasticsearch.elasticsearch.config.mode;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.edu_sharing.elasticsearch.elasticsearch.core.*;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationRunner;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationService;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.Application;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;


@Configuration
@ConditionalOnProperty(name = "mode", havingValue = "migration-only")
public class MigrationConfiguration {


    @Bean
    public ApplicationState applicationState(){
        return () -> true;
    }

    @Bean
    public AdminService adminService(ElasticsearchClient client, Collection<IndexConfiguration> indexConfigurations, AdminServiceSynonyms adminServiceSynonyms){
        AdminService adminService = new AdminService(client, indexConfigurations, adminServiceSynonyms);
        adminService.setAutocreateIndex(false);
        return adminService;
    }

    @Bean
    public MigrationRunner migrationRunner(MigrationService migrationService){
        MigrationRunner migrationRunner = new MigrationRunner(migrationService);
        migrationRunner.setExitAfterExecution(true);
        return migrationRunner;
    }

}
