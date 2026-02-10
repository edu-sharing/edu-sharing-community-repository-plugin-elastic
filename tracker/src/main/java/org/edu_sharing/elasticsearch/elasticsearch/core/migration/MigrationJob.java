package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import java.io.IOException;

/**
 * Represents a migration job interface for handling the migration of Elasticsearch indices
 * and associated data within a specific versioning and workflow context. Implementations of this
 * interface provide the necessary methods to manage the migration of workspace, transaction, and
 * authority indices as well as progress tracking and callback interactions.
 * </br>
 * The interface outlines the migration workflow steps, including setting migration callbacks,
 * checking migration requirements, and retrieving migration progress content.
 * </br>
 * Methods in this interface enable consuming classes to:
 * - Retrieve relevant information about source indices for migration.
 * - Access the current migration version and index target.
 * - Determine whether specific types of migrations, such as document or authorities migration, are required.
 * - Set and retrieve migration state with progress callback integration.
 * - Construct migration tasks and handle associated indices for transactions and authorities.
 */
public interface MigrationJob {
    String getSourceWorkspaceIndex();

    String getSourceTransactionIndex();

    String getSourceAuthoritiesIndex();

    /**
     * Retrieves the current migration version.
     * The version serves as a unique identifier for migration-related operations
     * and is used as a postfix for workspace and transaction indices.
     *
     * @return the current migration version as a String.
     */
    String getVersion();

    boolean isRequiresDocumentMigration();

    boolean isRequiresAuthoritiesMigration();

    String getMigrationTransactionIndex();

    String getMigrationTransactionAuthoritiesIndex();

    MigrationStep setStateMigrationCallback(MigrationState migrationState, MigrationCallback migrationCallback, String progressContent, String message) throws IOException;

    String getProgressContentFromStateMigrationCallback(MigrationState migrationState);
}