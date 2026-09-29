package org.edu_sharing.elasticsearch.elasticsearch.core.migration.jobs;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.edu_sharing.elasticsearch.TrackerAvailabilityTickService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationCallback;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CallbackMigrationJobTest {

    @Mock
    private ElasticsearchClient client;
    @Mock
    private TrackerAvailabilityTickService tickService;

    private String tickName;

    private MigrationContext newContext() {
        String[] contentHolder = new String[1];
        return MigrationContext.builder()
                .migrationContentSupplier(() -> contentHolder[0])
                .migrationContentConsumer(c -> contentHolder[0] = c)
                .build();
    }

    @Test
    void onProgressStateTicksOnceForEveryCallbackItRuns() {
        // Arrange
        MigrationCallback first = mock(MigrationCallback.class);
        MigrationCallback second = mock(MigrationCallback.class);
        CallbackMigrationJob job = new CallbackMigrationJob(client, List.of(first, second), tickService);
        tickName = MigrationJob.tickName(job);

        // Act
        job.onProgressState(newContext());

        // Assert: a stuck migration callback must be caught independently of the reindex/
        // tracker steps that ran before it.
        verify(tickService, times(2)).tick(tickName);
        verify(first).onMigrationCallback(any(), eq(client));
        verify(second).onMigrationCallback(any(), eq(client));
    }

    @Test
    void onProgressStateNeverTicksWhenThereAreNoCallbacksToRun() {
        // Arrange
        CallbackMigrationJob job = new CallbackMigrationJob(client, List.of(), tickService);
        tickName = MigrationJob.tickName(job);

        // Act
        job.onProgressState(newContext());

        // Assert
        verify(tickService, never()).tick(tickName);
    }

    @Test
    void onExitStateClearsTheTickSoTheCompletedStepCannotAgeOutLater() {
        // Arrange
        CallbackMigrationJob job = new CallbackMigrationJob(client, List.of(), tickService);
        tickName = MigrationJob.tickName(job);

        // Act
        job.onExitState(newContext());

        // Assert
        verify(tickService).clear(tickName);
    }
}
