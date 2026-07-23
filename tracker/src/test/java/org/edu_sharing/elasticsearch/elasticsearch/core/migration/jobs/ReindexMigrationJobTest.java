package org.edu_sharing.elasticsearch.elasticsearch.core.migration.jobs;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.tasks.ElasticsearchTasksClient;
import co.elastic.clients.elasticsearch.tasks.GetTasksResponse;
import co.elastic.clients.elasticsearch.tasks.TaskInfo;
import org.edu_sharing.elasticsearch.TrackerAvailabilityTickService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReindexMigrationJobTest {

    @Mock
    private ElasticsearchClient client;
    @Mock
    private ElasticsearchTasksClient tasksClient;
    @Mock
    private TrackerAvailabilityTickService tickService;

    private ReindexMigrationJob job;
    private String tickName;

    @BeforeEach
    void setUp() {
        job = new ReindexMigrationJob(
                MigrationStep.REINDEX_WORKSPACE_INDEX_PROGRESS_STEP,
                client,
                "workspace_9.1",
                "workspace_10.0",
                null,
                1000,
                -1f,
                tickService);
        tickName = MigrationJob.tickName(job);
        // onEnterState() (which sets taskId via client.reindex()) is out of scope here - inject
        // it directly so onProgressState()/onExitState() can be tested in isolation.
        ReflectionTestUtils.setField(job, "taskId", "task-123");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onProgressStateTicksBeforeEveryPollOfElasticsearch() throws Exception {
        // Arrange: task is already completed on the very first poll.
        GetTasksResponse response = mock(GetTasksResponse.class);
        TaskInfo taskInfo = mock(TaskInfo.class);
        when(response.task()).thenReturn(taskInfo);
        when(response.completed()).thenReturn(true);
        when(client.tasks()).thenReturn(tasksClient);
        when(tasksClient.get(any(Function.class))).thenReturn(response);

        // Act
        job.onProgressState(null);

        // Assert: the liveness tick must happen on every poll pass, independent of how long a
        // single reindex task takes to complete.
        verify(tickService).tick(tickName);
    }

    @Test
    void onExitStateClearsTheTickSoTheCompletedStepCannotAgeOutLater() {
        // Act
        job.onExitState(null);

        // Assert
        verify(tickService).clear(tickName);
        verify(tickService, never()).tick(tickName);
    }
}
