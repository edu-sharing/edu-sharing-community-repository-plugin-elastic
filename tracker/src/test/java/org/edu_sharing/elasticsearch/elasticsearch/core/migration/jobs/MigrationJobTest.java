package org.edu_sharing.elasticsearch.elasticsearch.core.migration.jobs;

import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationContext;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationStep;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationJobTest {

    @Test
    void tickNameIsStableSoExistingLivenessEntriesKeepMatchingAcrossDeploys() {
        // Arrange: a minimal MigrationJob stub - only getMigrationStep() matters here.
        MigrationJob job = new MigrationJob() {
            @Override
            public MigrationStep getMigrationStep() {
                return MigrationStep.MIGRATE_DOCUMENTS_PROGRESS_STEP;
            }

            @Override
            public void onEnterState(MigrationContext context) {
            }

            @Override
            public void onProgressState(MigrationContext context) {
            }

            @Override
            public void onExitState(MigrationContext context) {
            }
        };

        // Act & Assert
        assertThat(MigrationJob.tickName(job)).isEqualTo("migration:MIGRATE_DOCUMENTS_PROGRESS_STEP");
    }
}
