package org.edu_sharing.elasticsearch.elasticsearch.core.migration.jobs;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.TrackerAvailabilityTickService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationCallback;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationContext;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationStep;

import java.util.List;
import java.util.stream.IntStream;

@Slf4j
@RequiredArgsConstructor
public class CallbackMigrationJob implements MigrationJob {
    @Getter
    private final MigrationStep migrationStep = MigrationStep.ON_MIGRATION_CALLBACK_PROGRESS_STEP;
    private final ElasticsearchClient client;
    private final List<MigrationCallback> migrationCallbacks;
    private final TrackerAvailabilityTickService tickService;


    @Override
    public void onEnterState(MigrationContext context) {}

    @Override
    public void onProgressState(MigrationContext context) {
        if(migrationCallbacks.isEmpty()){
            return;
        }

        String migrationCallbackProgressContent = context.getMigrationContent();

        int startIndex = 0;
        if (migrationCallbackProgressContent != null) {
            String migrationCallbackIndex = migrationCallbackProgressContent.split(":")[0];
            startIndex = Integer.parseInt(migrationCallbackIndex);
        }

        IntStream.range(startIndex, migrationCallbacks.size())
                .forEach(i -> {
                    tickService.tick(MigrationJob.tickName(this));
                    MigrationContext callbackContext = context.toBuilder()
                            .migrationContentSupplier(() ->{
                                String content = context.getMigrationContent();
                                if(content ==null){
                                    return null;
                                }
                                int index = content.indexOf(":");
                                if(index == -1){
                                    return null;
                                }
                                return content.substring(index + 1);
                            } )
                            .migrationContentConsumer(content -> context.setMigrationContent(i + ":" + content))
                            .build();

                    MigrationCallback callback = migrationCallbacks.get(i);
                    log.info("Run Migration callback {}: {}", callback.getName(), callback.getClass().getSimpleName());
                    callback.onMigrationCallback(callbackContext, client);
                    context.setMigrationContent(Integer.toString(i+1));
                });
    }
    @Override
    public void onExitState(MigrationContext context) {
        // callbacks concluded (or there were none to run) - clear for the same reason as
        // ReindexMigrationJob.onExitState().
        tickService.clear(MigrationJob.tickName(this));
    }
}
