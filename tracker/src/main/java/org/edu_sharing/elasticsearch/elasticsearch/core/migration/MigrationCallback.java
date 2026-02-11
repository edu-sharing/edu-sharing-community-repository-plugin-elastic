package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.edu_sharing.elasticsearch.tracker.TransactionTracker;
import org.edu_sharing.elasticsearch.tracker.TransactionTrackerBase;

import java.util.Map;

/**
 * Interface defining a callback mechanism for custom actions during the migration process.
 * Implementations of this interface can be used to add specific logic, validations, data
 * transformations, or post-migration tasks at designated stages of a migration workflow.
 * </br>
 * The callback is invoked by the migration workflow, which is managed by {@link MigrationJob}.
 * It provides access to the {@link MigrationJob} details, the current {@link MigrationState},
 * and an instance of {@link ElasticsearchClient} to enable seamless interactions with the
 * underlying system.
 * </br>
 * Methods:
 * - {@code getName()} is used to retrieve the name of the callback, which can be used for
 *   identification or logging purposes. The name needs to be unique over all migration callbacks.
 * - {@code onMigrationCallback(MigrationJob migrationJob, MigrationState migrationState, ElasticsearchClient client)}
 *   is executed during the migration process to perform custom logic, using the provided migration
 *   job details, state information, and the Elasticsearch client.
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
     * Defines a callback triggered during the migration process to perform custom logic.
     * The callback is invoked at a specific stage of the migration workflow, providing
     * access to the migration job details, the current migration state, and an Elasticsearch client.
     *
     * @param migrationJob   the migration job providing information about the indices, migration requirements,
     *                       and versioning details. It also facilitates setting migration state and progress updates.
     * @param migrationState the current state of the migration process, including progress, updates, and status messages.
     * @param client         the Elasticsearch client used for interacting with the Elasticsearch system
     *                       during the migration process.
     */
    void onMigrationCallback(final MigrationJob migrationJob, final MigrationState migrationState, final ElasticsearchClient client, Map<String, TransactionTrackerBase> trackerRegistry, TransactionTracker transactionTracker);
}
