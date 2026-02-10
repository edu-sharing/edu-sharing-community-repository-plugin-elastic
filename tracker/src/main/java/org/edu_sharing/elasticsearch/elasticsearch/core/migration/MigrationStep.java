package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

public enum MigrationStep {
    INIT_PROGRESS_STEP(0, "Not Started"),
    REINDEX_WORKSPACE_INDEX_PROGRESS_STEP(1, "Reindex Workspace"),
    REINDEX_TRANSACTIONS_INDEX_PROGRESS_STEP(2, "Reindex transactions"),
    REINDEX_AUTHORITIES_INDEX_PROGRESS_STEP(3, "ReIndex Authorities"),
    MIGRATE_AUTHORITIES_INDEX_PROGRESS_STEP(4, "Migrate Authorities"),
    ON_MIGRATION_CALLBACK_PROGRESS_STEP(5, "On Migration Callback"),
    MIGRATE_DOCUMENTS_PROGRESS_STEP(6, "Migrate Documents"),
    COMPLETED_PROGRESS_STEP(7, "Completed");


    public final int value;
    public final String message;

    MigrationStep(int value, String message) {
        this.value = value;
        this.message = message;
    }

    public static MigrationStep valueOf(int value) {
        return switch (value) {
            case 0 -> INIT_PROGRESS_STEP;
            case 1 -> REINDEX_WORKSPACE_INDEX_PROGRESS_STEP;
            case 2 -> REINDEX_TRANSACTIONS_INDEX_PROGRESS_STEP;
            case 3 -> REINDEX_AUTHORITIES_INDEX_PROGRESS_STEP;
            case 4 -> MIGRATE_AUTHORITIES_INDEX_PROGRESS_STEP;
            case 5 -> ON_MIGRATION_CALLBACK_PROGRESS_STEP;
            case 6 -> MIGRATE_DOCUMENTS_PROGRESS_STEP;
            case 7 -> COMPLETED_PROGRESS_STEP;
            default -> throw new IllegalArgumentException(value + " is not an valid value");
        };
    }
}
