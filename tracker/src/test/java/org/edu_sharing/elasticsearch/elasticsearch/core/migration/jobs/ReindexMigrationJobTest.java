package org.edu_sharing.elasticsearch.elasticsearch.core.migration.jobs;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.ReindexResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.tasks.ElasticsearchTasksClient;
import co.elastic.clients.elasticsearch.tasks.GetTasksResponse;
import co.elastic.clients.elasticsearch.tasks.TaskInfo;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import org.edu_sharing.elasticsearch.TrackerAvailabilityTickService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationContext;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationException;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationStep;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReindexMigrationJobTest {

    @Mock
    private ElasticsearchClient client;
    @Mock
    private ElasticsearchIndicesClient indicesClient;
    @Mock
    private ElasticsearchTasksClient tasksClient;
    @Mock
    private TrackerAvailabilityTickService tickService;
    @Mock
    private MigrationContext context;

    private ReindexMigrationJob job;
    private String tickName;

    @BeforeEach
    void setUp() {
        job = new ReindexMigrationJob(
                MigrationStep.REINDEX_WORKSPACE_INDEX_PROGRESS_STEP,
                client,
                "workspace_9.1",
                "workspace_10.0",
                1000,
                -1f,
                tickService);
        tickName = MigrationJob.tickName(job);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onProgressStateTicksBeforeEveryPollOfElasticsearch() throws Exception {
        // Arrange: task is already completed on the very first poll.
        ReflectionTestUtils.setField(job, "taskId", "task-123");
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

    @Test
    @SuppressWarnings("unchecked")
    void onEnterStateSkipsReindexWhenSourceIndexDoesNotExist() throws Exception {
        // Arrange
        when(context.getMigrationContent()).thenReturn(null);
        when(client.indices()).thenReturn(indicesClient);
        BooleanResponse exists = mock(BooleanResponse.class);
        when(exists.value()).thenReturn(false);
        when(indicesClient.exists(any(Function.class))).thenReturn(exists);

        // Act
        job.onEnterState(context);
        job.onProgressState(context);

        // Assert: nothing to reindex, so no task is started or polled.
        verify(context, never()).setMigrationContent(any());
        verifyNoInteractions(tasksClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onEnterStateStartsReindexWhenSourceIndexExists() throws Exception {
        // Arrange
        when(context.getMigrationContent()).thenReturn(null);
        when(client.indices()).thenReturn(indicesClient);
        BooleanResponse exists = mock(BooleanResponse.class);
        when(exists.value()).thenReturn(true);
        when(indicesClient.exists(any(Function.class))).thenReturn(exists);
        ReindexResponse reindexResponse = mock(ReindexResponse.class);
        when(reindexResponse.task()).thenReturn("task-123");
        when(client.reindex(any(Function.class))).thenReturn(reindexResponse);

        // Act
        job.onEnterState(context);

        // Assert
        verify(context).setMigrationContent("task-123");
    }

    @Test
    void onEnterStateResumesExistingTaskWithoutCheckingSourceIndex() {
        // Arrange: a migration content already stored from a previous run takes precedence.
        when(context.getMigrationContent()).thenReturn("existing-task-id");

        // Act
        job.onEnterState(context);

        // Assert
        assertThat(ReflectionTestUtils.getField(job, "taskId")).isEqualTo("existing-task-id");
        verifyNoInteractions(indicesClient);
        verify(context, never()).setMigrationContent(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void onProgressStateFailsWithTheActualElasticsearchErrorInsteadOfAnEmptyTaskInfo() throws Exception {
        // Arrange
        ReflectionTestUtils.setField(job, "taskId", "task-123");
        GetTasksResponse response = mock(GetTasksResponse.class);
        TaskInfo taskInfo = mock(TaskInfo.class);
        ErrorCause error = mock(ErrorCause.class);
        when(error.type()).thenReturn("index_not_found_exception");
        when(error.reason()).thenReturn("no such index [authorities_9.0]");
        when(response.task()).thenReturn(taskInfo);
        when(response.error()).thenReturn(error);
        when(client.tasks()).thenReturn(tasksClient);
        when(tasksClient.get(any(Function.class))).thenReturn(response);

        // Act & Assert
        assertThatThrownBy(() -> job.onProgressState(null))
                .isInstanceOf(MigrationException.class)
                .hasMessageContaining("index_not_found_exception")
                .hasMessageContaining("no such index [authorities_9.0]");
    }
}
