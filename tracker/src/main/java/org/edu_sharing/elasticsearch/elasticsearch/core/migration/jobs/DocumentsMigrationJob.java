package org.edu_sharing.elasticsearch.elasticsearch.core.migration.jobs;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.elasticsearch.core.AdminService;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceFactory;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceInterface;
import org.edu_sharing.elasticsearch.edu_sharing.api.RepositoryAvailabilityProbe;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationContext;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationException;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationStep;
import org.edu_sharing.elasticsearch.tracker.core.TrackerConfig;
import org.edu_sharing.elasticsearch.tracker.core.TrackerExecutorFactory;
import org.edu_sharing.elasticsearch.tracker.core.TrackerRegistry;
import org.edu_sharing.elasticsearch.tracker.core.TrackingExecutor;
import org.edu_sharing.elasticsearch.tracker.strategy.CommitTimeStatus;
import org.edu_sharing.elasticsearch.tracker.strategy.MaxCommitTimeStrategy;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@RequiredArgsConstructor
public class DocumentsMigrationJob implements MigrationJob {
    @Getter
    private final MigrationStep migrationStep = MigrationStep.MIGRATE_DOCUMENTS_PROGRESS_STEP;

    private final AdminService adminService;
    private final String migrationTransactionIndex;
    private final TrackerRegistry trackerRegistry;
    private final Collection<Class<? extends TrackerConfig<?, ? extends CommitTimeStatus>>> migrationTrackerConfigTypes;
    private final StatusIndexServiceFactory statusIndexServiceFactory;
    private final TrackerExecutorFactory trackerExecutorFactory;
    private final RepositoryAvailabilityProbe repositoryAvailabilityProbe;

    private Map<TrackerConfig<?, ?>, TrackingExecutor<?>> trackingExecutors;


    @Override
    public void onEnterState(MigrationContext context) {
        IndexConfiguration indexConfiguration = new IndexConfiguration(req -> req.index(migrationTransactionIndex));
        boolean createdIndex;
        try {
            createdIndex = adminService.createIndex(indexConfiguration);
        } catch (IOException e) {
            throw new MigrationException(String.format("Failed to create migration transaction index %s: %s", migrationTransactionIndex, e.getMessage()), e);
        }

        Map<TrackerConfig<?, ?>, Long> indexStartMap = new HashMap<>();
        Set<TrackerConfig<?, ?>> activeTrackerConfigs = trackerRegistry.getActiveTrackerConfigs();
        if (createdIndex) {
            // copy index state from source to migration transaction index for those which won't be migrated
            for (TrackerConfig<?, ?> trackerConfig : activeTrackerConfigs) {
                if (!migrationTrackerConfigTypes.contains(trackerConfig.getClass())) {
                    copyIndexState(context, trackerConfig);
                }

                Long commitTime = getIndexStateCommitTime(context, trackerConfig);
                if (commitTime != null) {
                    indexStartMap.put(trackerConfig, commitTime);
                }
            }
            context.setMigrationContent(indexStartMap.entrySet()
                    .stream()
                    .map(entry -> entry.getKey().getName() + ":" + entry.getValue())
                    .reduce((a, b) -> String.join(";", a, b))
                    .orElse(null));

        } else {
            String migrationContent = context.getMigrationContent();
            if (migrationContent == null) {
                for (TrackerConfig<?, ?> trackerConfig : activeTrackerConfigs) {
                    Long commitTime = getIndexStateCommitTime(context, trackerConfig);
                    if (commitTime != null) {
                        indexStartMap.put(trackerConfig, commitTime);
                    }
                }
                context.setMigrationContent(indexStartMap.entrySet()
                        .stream()
                        .map(entry -> entry.getKey().getName() + ":" + entry.getValue())
                        .reduce("", (a, b) -> a + ";" + b));
            } else {
                String[] indexStarts = migrationContent.split(";");
                for (String indexStart : indexStarts) {
                    String[] parts = indexStart.split(":");
                    if (parts.length == 2) {
                        indexStartMap.put(trackerRegistry.getTrackerConfigByName(parts[0]), Long.valueOf(parts[1]));
                    }
                }
            }
        }

        trackingExecutors = trackerExecutorFactory.createTrackerExecutors(activeTrackerConfigs.stream()
                .filter(trackerConfig -> migrationTrackerConfigTypes.contains(trackerConfig.getClass()))
                .toList(), migrationTransactionIndex, (trackerConfig -> {
            Long indexStartValue = indexStartMap.get(trackerConfig);
            if (indexStartValue == null) {
                throw new IllegalStateException("No index start value found for tracker " + trackerConfig.getName());
            }
            return new MaxCommitTimeStrategy(indexStartValue);
        }));
    }

    private <STATE> void copyIndexState(MigrationContext context, TrackerConfig<?, STATE> trackerConfig) {
        StatusIndexServiceInterface<STATE> sourceStatusIndexService = statusIndexServiceFactory.createStateService(trackerConfig.getStatusClass(), trackerConfig.getName(), context.getTargetTrackerStateIndex());
        StatusIndexServiceInterface<STATE> targetStatusIndexService = statusIndexServiceFactory.createStateService(trackerConfig.getStatusClass(), trackerConfig.getName(), migrationTransactionIndex);
        try {
            targetStatusIndexService.setState(sourceStatusIndexService.getState());
        } catch (IOException e) {
            throw new MigrationException(String.format("Failed to copy state from %s to %s: %s", context.getSourceTrackerStateIndex(), migrationTransactionIndex, e.getMessage()), e);
        }
    }

    private <STATE> Long getIndexStateCommitTime(MigrationContext context, TrackerConfig<?, STATE> trackerConfig) {
        if (!CommitTimeStatus.class.isAssignableFrom(trackerConfig.getStatusClass())) {
            return null;
        }

        StatusIndexServiceInterface<STATE> stateService = statusIndexServiceFactory.createStateService(trackerConfig.getStatusClass(), trackerConfig.getName(), context.getTargetTrackerStateIndex());
        try {
            return ((CommitTimeStatus) stateService.getState()).getCommitTime();
        } catch (IOException e) {
            throw new MigrationException(String.format("Failed to get commit time for tracker %s: %s", trackerConfig.getName(), e.getMessage()), e);
        }
    }

    @Override
    public void onProgressState(MigrationContext context) {

        // The reindex/callback phases ran ES-only; repository access starts here. Wait for the
        // repository only now (and only if there is anything to track via the repository).
        if (!trackingExecutors.isEmpty()) {
            repositoryAvailabilityProbe.waitUntilAvailable();
        }

        List<Future<?>> futures = new ArrayList<>();
        trackingExecutors.forEach((trackerConfig, trackingExecutor) -> {
            ExecutorService executorService = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r);
                t.setName(trackerConfig.getName()); // Threadname aus Config-Key
                t.setDaemon(true);
                return t;
            });

            Future<?> submit = executorService.submit(trackingExecutor::track);
            futures.add(submit);
        });


        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MigrationException("Migration was interrupted", e);
            } catch (Exception e) {
                throw new MigrationException("Migration tracking failed: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void onExitState(MigrationContext context) {

    }
}
