package org.edu_sharing.elasticsearch.elasticsearch.core.migration.jobs;

import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationContext;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationStep;

public class CompleteMigrationJob implements MigrationJob{
    @Override
    public MigrationStep getMigrationStep() {
        return MigrationStep.COMPLETED_PROGRESS_STEP;
    }

    @Override
    public void onEnterState(MigrationContext context) {

    }

    @Override
    public void doOnProgressState(MigrationContext context) {

    }

    @Override
    public void doOnExitState(MigrationContext context) {

    }
}
