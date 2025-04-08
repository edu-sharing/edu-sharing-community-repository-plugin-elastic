package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

public enum MigrationStep {
    INIT_PROGRESS_STEP(0, "Not Started"),
    REINDEX_WORKSPACE_INDEX_PROGRESS_STEP(1, "Reindex Workspace"),
    REINDEX_TRANSACTIONS_INDEX_PROGRESS_STEP(2, "Reindex transactions"),
    REINDEX_AUTHORITIES_INDEX_PROGRESS_STEP(3, "ReIndex Authorities"),
    MIGRATE_AUTHORITIES_INDEX_PROGRESS_STEP(4, "Migrate Authorities"),
    MIGRATE_DOCUMENTS_PROGRESS_STEP(5, "Migrate Documents"),
    COMPLETED_PROGRESS_STEP(6, "Completed");


    public final int value;
    public final String message;

    MigrationStep(int value, String message) {
        this.value = value;
        this.message = message;
    }

    public static MigrationStep valueOf(int value) {
        switch (value){
            case 0: return INIT_PROGRESS_STEP;
            case 1: return REINDEX_WORKSPACE_INDEX_PROGRESS_STEP;
            case 2: return REINDEX_TRANSACTIONS_INDEX_PROGRESS_STEP;
            case 3: return REINDEX_AUTHORITIES_INDEX_PROGRESS_STEP;
            case 4: return MIGRATE_AUTHORITIES_INDEX_PROGRESS_STEP;
            case 5: return MIGRATE_DOCUMENTS_PROGRESS_STEP;
            case 6: return COMPLETED_PROGRESS_STEP;
            default: throw new IllegalArgumentException(value + " is not an valid value");
        }
    }
}
