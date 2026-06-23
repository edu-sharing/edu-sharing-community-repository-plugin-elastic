package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import java.util.Arrays;

public enum MigrationStep {
    REINDEX_TRANSACTIONS_INDEX_PROGRESS_STEP(0, "Reindex Transactions"),
    REINDEX_WORKSPACE_INDEX_PROGRESS_STEP(1, "Reindex Workspace"),
    REINDEX_AUTHORITIES_INDEX_PROGRESS_STEP(2, "ReIndex Authorities"),
    ON_MIGRATION_CALLBACK_PROGRESS_STEP(3, "On Migration Callback"),
    MIGRATE_DOCUMENTS_PROGRESS_STEP(4, "Migrate Documents"),
    COMPLETED_PROGRESS_STEP(5, "Completed");


    public final int value;
    public final String message;

    MigrationStep(int value, String message) {
        this.value = value;
        this.message = message;
    }

    public static MigrationStep valueOf(int value) {
        return Arrays.stream(MigrationStep.values())
                .filter(step -> step.value == value)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(value + " is not an valid value"));
    }
}
