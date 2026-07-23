package org.edu_sharing.elasticsearch.elasticsearch.core.migration.jobs;


import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationContext;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationStep;

public interface MigrationJob {

    MigrationStep getMigrationStep();
    void onEnterState(MigrationContext context);

    void onProgressState(MigrationContext context);

    void onExitState(MigrationContext context);

    /**
     * Key used to report liveness progress for this migration step via
     * {@link org.edu_sharing.elasticsearch.TrackerAvailabilityTickService#tick(String)},
     * so migration-only mode is monitored the same way as the regular trackers.
     */
    static String tickName(MigrationJob job) {
        return "migration:" + job.getMigrationStep().name();
    }
}