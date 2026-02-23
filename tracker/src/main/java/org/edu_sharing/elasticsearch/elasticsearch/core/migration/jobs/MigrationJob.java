package org.edu_sharing.elasticsearch.elasticsearch.core.migration.jobs;


import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationContext;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationStep;

public interface MigrationJob {

    MigrationStep getMigrationStep();
    void onEnterState(MigrationContext context);

    void onProgressState(MigrationContext context);

    void onExitState(MigrationContext context);
}