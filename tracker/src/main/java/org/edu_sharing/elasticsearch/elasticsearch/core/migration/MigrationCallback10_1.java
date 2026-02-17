package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceFactory;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceInterface;
import org.edu_sharing.elasticsearch.tracker.acl.AclTracker;
import org.edu_sharing.elasticsearch.tracker.auth.AuthoritiesTracker;
import org.edu_sharing.elasticsearch.tracker.collection.CollectionSyncTracker;
import org.edu_sharing.elasticsearch.tracker.core.TrackerConfig;
import org.edu_sharing.elasticsearch.tracker.core.TrackerRegistry;
import org.edu_sharing.elasticsearch.tracker.main.MainTracker;
import org.edu_sharing.elasticsearch.tracker.preview.PreviewTracker;
import org.edu_sharing.elasticsearch.tracker.statistics.StatisticsTracker;
import org.edu_sharing.elasticsearch.tracker.usage.UsageSyncTracker;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class MigrationCallback10_1 implements MigrationCallback {

    private final StatusIndexServiceFactory statusIndexServiceFactory;
    private final TrackerRegistry trackerRegistry;

    List<TrackerMigrationInfo<?>> trackerMigrationInfos = List.of(
            new TrackerMigrationInfo<>(MainTracker.class, "1"),
            new TrackerMigrationInfo<>(AuthoritiesTracker.class, "1"),
            new TrackerMigrationInfo<>(CollectionSyncTracker.class, "1"),
            new TrackerMigrationInfo<>(PreviewTracker.class, "1"),
            new TrackerMigrationInfo<>(UsageSyncTracker.class, "1"),
            new TrackerMigrationInfo<>(AclTracker.class, "2"),
            new TrackerMigrationInfo<>(StatisticsTracker.class, "3")
    );

    record TrackerMigrationInfo<STATE>(Class<? extends TrackerConfig<?, STATE>> trackerConfigClass,
                                       String sourceDocId) {
    }

    @Override
    public String getName() {
        return "MigrationCallback10_1";
    }

    @Override
    public void onMigrationCallback(MigrationContext context, ElasticsearchClient client) {
        String migrationContent = context.getMigrationContent();
        int progress = StringUtils.isNotBlank(migrationContent) ? Integer.parseInt(migrationContent) : 0;

        String trackerIndex = context.getTargetTrackerStateIndex();
        for (int i = progress; i < trackerMigrationInfos.size(); i++) {
            TrackerMigrationInfo<?> trackerMigrationInfo = trackerMigrationInfos.get(i);
            migrateTrackerIndex(trackerIndex, trackerMigrationInfo);
            progress++;
            context.setMigrationContent(String.valueOf(progress));
        }

        for (int i = progress - trackerMigrationInfos.size(); i < trackerMigrationInfos.size(); i++) {
            TrackerMigrationInfo<?> trackerMigrationInfo = trackerMigrationInfos.get(i);
            deleteTrackerIndex(trackerIndex, trackerMigrationInfo);
            progress++;
            context.setMigrationContent(String.valueOf(progress));
        }
    }

    private <STATE> void migrateTrackerIndex(String index, TrackerMigrationInfo<STATE> trackerMigrationInfo) {
        TrackerConfig<?, STATE> trackerConfig = trackerRegistry.getTrackerConfigByClass(trackerMigrationInfo.trackerConfigClass);
        StatusIndexServiceInterface<STATE> sourceState = statusIndexServiceFactory.createStateService(trackerConfig.getStatusClass(), trackerMigrationInfo.sourceDocId, index);
        StatusIndexServiceInterface<STATE> targetState = statusIndexServiceFactory.createStateService(trackerConfig.getStatusClass(), trackerConfig.getName(), index);

        try {
            log.info("Migrate tracker index for tracker {}", trackerConfig.getName());
            STATE state = sourceState.getState();
            targetState.setState(state);
        } catch (IOException e) {
            throw new MigrationException(String.format("Failed to migrate tracker index for tracker %s", trackerConfig.getName()), e);
        }
    }

    private <STATE> void deleteTrackerIndex(String index, TrackerMigrationInfo<STATE> trackerMigrationInfo) {
        TrackerConfig<?, STATE> trackerConfig = trackerRegistry.getTrackerConfigByClass(trackerMigrationInfo.trackerConfigClass);
        StatusIndexServiceInterface<STATE> sourceState = statusIndexServiceFactory.createStateService(trackerConfig.getStatusClass(), trackerMigrationInfo.sourceDocId, index);

        try {
            log.info("Delete tracker source index for tracker {}", trackerConfig.getName());
            sourceState.resetState();
        } catch (IOException e) {
            throw new MigrationException(String.format("Failed to delete tracker index for tracker %s", trackerConfig.getName()), e);
        }
    }
}
