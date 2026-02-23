package org.edu_sharing.elasticsearch.elasticsearch.core.migration.jobs;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch.tasks.GetTasksResponse;
import co.elastic.clients.elasticsearch.tasks.TaskInfo;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationContext;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationException;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationStep;

import java.io.IOException;
import java.util.Objects;


@Slf4j
@RequiredArgsConstructor
public class ReindexMigrationJob implements MigrationJob {
    @Getter
    private final MigrationStep migrationStep;
    private final ElasticsearchClient client;
    private final String sourceIndex;
    private final String targetIndex;


    private String taskId;

    @Override
    public void onEnterState(MigrationContext context) {
        Objects.requireNonNull(sourceIndex, "sourceIndex must not be null");
        Objects.requireNonNull(targetIndex, "sourceIndex must not be null");

        taskId = context.getMigrationContent();
        if(StringUtils.isNotBlank(taskId)){
            return;
        }

        try {
            taskId = client.reindex(req -> req
                            .waitForCompletion(false)
                            .conflicts(Conflicts.Proceed)
                            .source(src -> src.index(sourceIndex))
                            .dest(dest -> dest.index(targetIndex)))
                    .task();
            context.setMigrationContent(taskId);
        }catch (IOException ex){
            throw new MigrationException(String.format("Failed to start reindex from %s to %s: %s", sourceIndex, targetIndex, ex.getMessage()), ex);
        }
    }

    @Override
    public void onProgressState(MigrationContext context) {
        while(true) {
            try {
                GetTasksResponse tasksResponse = client.tasks().get(req -> req.taskId(taskId));
                TaskInfo task = tasksResponse.task();
                if (tasksResponse.error() != null) {
                    throw new MigrationException(String.format("Task failed: %s", task));
                }

                if (Boolean.TRUE.equals(task.cancelled())) {
                    throw new MigrationException(String.format("Task was cancelled: %s", task));
                }

                if (tasksResponse.completed()) {
                    return;
                }
                log.info("reindexing {}...", targetIndex);
                log.info("Task progress: {}", task);
                Thread.sleep(5000);

            } catch (IOException ex) {
                throw new MigrationException(String.format("Failed to get task %s: %s", taskId, ex.getMessage()), ex);
            } catch (InterruptedException e) {
                throw new MigrationException("Migration was interrupted", e);
            }
        }
    }

    @Override
    public void onExitState(MigrationContext context) {

    }
}

