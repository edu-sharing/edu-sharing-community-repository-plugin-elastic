package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

/**
 * A callback interface used during the migration process to execute custom logic at a particular stage.
 * The implementation of this callback can contain logic specific to the migration process, such as
 * additional validations, transformations, or post-migration actions.
 * </br>
 * The {@code MigrationCallback} is invoked during the migration process according to the workflow
 * orchestrated by the {@code CallbackMigrationJob}. It can access both the job details and a client instance
 * for further actions through the provided methods.
 * </br>
 * This interface allows for defining custom behaviors to be executed as part of the migration process,
 * ensuring flexibility and extensibility in handling migration scenarios.
 */
public interface MigrationCallback {

    /**
     * Retrieves the name of the migration callback.
     * </br>
     * Hint: The name needs to be unique over all migration callbacks.
     *
     * @return the name of the migration callback, which can be used for
     *         identification or logging purposes during the migration process.
     */
    String getName();
    /**
     * The method invoked during the migration process to execute custom logic, validations, or actions.
     * It provides access to the migration job details, migration context, and an Elasticsearch client to perform
     * workflow-specific operations. This method can be implemented to add custom functionality at a specific
     * stage in a migration workflow.
     *
     * @param context the migration context containing information about the state of the migration,
     *                including the source and target indices, configuration details, and tracker state.
     * @param client  the Elasticsearch client used for interacting with the underlying search
     *                infrastructure during the migration.
     */
    void onMigrationCallback(final MigrationContext context, final ElasticsearchClient client);
}
