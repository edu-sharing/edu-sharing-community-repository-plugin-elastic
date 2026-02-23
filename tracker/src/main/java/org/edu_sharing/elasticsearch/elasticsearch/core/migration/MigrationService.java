package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.AdminService;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceFactory;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.jobs.*;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.AppInfo;
import org.edu_sharing.elasticsearch.tracker.core.TrackerConfig;
import org.edu_sharing.elasticsearch.tracker.core.TrackerExecutorFactory;
import org.edu_sharing.elasticsearch.tracker.core.TrackerRegistry;
import org.edu_sharing.elasticsearch.tracker.strategy.CommiteTimeStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationService {
    private final AdminService adminService;
    private final IndexConfiguration migrationsIndex;
    private final ElasticsearchClient client;
    private final StatusIndexService<AppInfo> appInfoStatusService;
    private final TrackerExecutorFactory trackerExecutorFactory;
    private final StatusIndexServiceFactory statusIndexServiceFactory;
    private final List<MigrationInfo> migrationInfos;
    private final TrackerRegistry trackerRegistry;

    public void runMigration() throws IOException, InterruptedException {
        AppInfo appInfo = getAppInfo();

        String latestVersion = migrationInfos.get(migrationInfos.size() - 1).getVersion();
        String currentVersion = appInfo.getTrackerVersion();

        if (Objects.equals(latestVersion, currentVersion)) {
            log.info("Current version {} is latest version, doing no migration.", currentVersion);
            return;
        }

        int startIndex = IntStream.range(0, migrationInfos.size())
                .filter(i -> migrationInfos.get(i).getVersion().equals(currentVersion))
                .findFirst()
                .orElse(-1) + 1;

        Set<Class<? extends TrackerConfig<?, ? extends CommiteTimeStatus>>> migrationTracker = IntStream.range(startIndex, migrationInfos.size())
                .mapToObj(migrationInfos::get)
                .map(MigrationInfo::getMigrateTrackerConfigs)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        // validate callbacks have to be a unique name
        Set<String> knownMigrationCallbacks = new HashSet<>();
        migrationInfos.stream()
                .map(MigrationInfo::getCallback)
                .filter(Objects::nonNull)
                .forEach(callback -> {
                    if (knownMigrationCallbacks.contains(callback.getName())) {
                        throw new IllegalStateException("Migration callback with name " + callback.getName() + " is already registered!");
                    }
                    knownMigrationCallbacks.add(callback.getName());
                });

        List<MigrationCallback> migrationCallbacks = IntStream.range(startIndex, migrationInfos.size())
                .mapToObj(migrationInfos::get)
                .map(MigrationInfo::getCallback)
                .filter(Objects::nonNull)
                .toList();


        String sourceWorkspaceIndex = currentVersion == null ? "workspace" : "workspace_" + currentVersion;
        String sourceAuthoritiesIndex = currentVersion == null ? "authorities" : "authorities_" + currentVersion;
        String sourceTransactionIndex = currentVersion == null ? "transactions" : "transactions_" + currentVersion;

        if (adminService.indicesExists(sourceWorkspaceIndex, sourceTransactionIndex, sourceAuthoritiesIndex)) {

            while (!adminService.indecesConfiguredExist()) {
                log.info("waiting for indexes...");
                Thread.sleep(2000);
            }

            // we need to migrate
            MigrationJobRunner migrationJobRunner = new MigrationJobRunner(
                    sourceWorkspaceIndex,
                    sourceTransactionIndex,
                    sourceAuthoritiesIndex,
                    latestVersion,
                    migrationsIndex.getIndex(),
                    migrationTracker,
                    migrationCallbacks);
            migrationJobRunner.run();
        }

        appInfo.setTrackerVersion(latestVersion);
        appInfoStatusService.setState(appInfo);
        // nothing else to do so we can stop execution

    }


    public boolean requiresMigration() throws IOException {
        log.info("Check if migration is required");
        AppInfo appInfo = getAppInfo();

        String latestVersion = migrationInfos.get(migrationInfos.size() - 1).getVersion();
        String currentVersion = appInfo.getTrackerVersion();

        if (Objects.equals(latestVersion, currentVersion)) {
            log.info("elastic search is on the latest tracker version ({}).", latestVersion);
            return false;
        }

        String sourceWorkspaceIndex = currentVersion == null ? "workspace" : "workspace_" + currentVersion;
        String sourceTransactionIndex = currentVersion == null ? "transactions" : "transactions_" + currentVersion;

        if (adminService.indicesExists(sourceWorkspaceIndex, sourceTransactionIndex)) {
            log.info("Index \"{}\" and Index \"{}\" requires migration.", sourceWorkspaceIndex, sourceTransactionIndex);
            return true;
        } else {
            // no migration required we should set the appInfo
            log.info("Plain elastic search detected, no migration required");
            appInfo.setTrackerVersion(latestVersion);
            appInfoStatusService.setState(appInfo);
            return false;
        }
    }

    /**
     * @return return true if the migration is completed otherwise false
     * @throws IOException indicates that elasticsearch can't be reached
     */
    public boolean checkForMigrationStatus() throws IOException {
        log.info("Check for migration status");
        AppInfo appInfo = getAppInfo();
        String latestVersion = migrationInfos.get(migrationInfos.size() - 1).getVersion();
        String currentVersion = appInfo.getTrackerVersion();

        if (Objects.equals(latestVersion, currentVersion)) {
            log.info("Migration completed! Running on tracker version {}", latestVersion);
            return true;
        }

        MigrationState migrationState = getMigrationState(latestVersion);
        try {
            switch (MigrationStep.valueOf(migrationState.getProgressStep())) {
                case MIGRATE_DOCUMENTS_PROGRESS_STEP:
                case COMPLETED_PROGRESS_STEP:
                    log.info("Migration completed! Running on tracker version {}", latestVersion);
                    return true;
            }

            log.info("Migration in progress (version {}) {}: {}", latestVersion, MigrationStep.valueOf(migrationState.getProgressStep()), migrationState.getStatusMessage());
        } catch (IllegalArgumentException ignored) {
            log.warn("Unknown migration step {}", migrationState.getProgressStep());
        }
        return false;
    }

    private AppInfo getAppInfo() throws IOException {
        AppInfo appInfo = appInfoStatusService.getState();
        if (appInfo == null) {
            appInfo = new AppInfo();
            appInfo.setCreationDate(new Date());
        }
        return appInfo;
    }

    private MigrationState getMigrationState(String version) {
        try {
            return client.search(req -> req
                                    .index(migrationsIndex.getIndex())
                                    .query(q -> q.ids(id -> id.values(version)))
                            , MigrationState.class)
                    .hits()
                    .hits()
                    .stream()
                    .filter(x -> Objects.nonNull(x.source()))
                    .map(Hit::source)
                    .findFirst()
                    .orElse(new MigrationState());
        } catch (IOException e) {
            throw new MigrationException("Failed to get migration state", e);
        }
    }

    private void setMigrationState(String version, MigrationState migrationState) {
        try {
            log.info("Update MigrationState: {}", migrationState);
            client.index(req -> req
                    .index(migrationsIndex.getIndex())
                    .id(version)
                    .document(migrationState));
        } catch (IOException e) {
            throw new MigrationException("Failed to update migration state", e);
        }
    }


    class MigrationJobRunner {
        private final List<MigrationJob> jobs;
        private final MigrationContext context;

        private MigrationState migrationState;

        MigrationJobRunner(
                String sourceWorkspaceIndex,
                String sourceTrackerStateIndex,
                String sourceAuthoritiesIndex,
                String toVersion,
                String migrationsIndex,
                Set<Class<? extends TrackerConfig<?, ? extends CommiteTimeStatus>>> migrationTracker,
                List<MigrationCallback> migrationCallbacks
        ) {
            this.context = new MigrationContext(
                    sourceWorkspaceIndex,
                    sourceTrackerStateIndex,
                    sourceAuthoritiesIndex,
                    "workspace_" + toVersion,
                    "transactions_" + toVersion,
                    "authorities_" + toVersion,
                    toVersion,
                    migrationsIndex,
                    "migration_" + toVersion + "_tracker",
                    migrationTracker,
                    migrationCallbacks,
                    this::getMigrationContentDelegate,
                    this::setMigrationContentDelegate
            );

            jobs = List.of( // Jobs needs to be ordered by MigrationStep (see requires migration)
                    new ReindexMigrationJob(MigrationStep.REINDEX_WORKSPACE_INDEX_PROGRESS_STEP, client, context.getSourceWorkspaceIndex(), context.getTargetWorkspaceIndex()),
                    new ReindexMigrationJob(MigrationStep.REINDEX_AUTHORITIES_INDEX_PROGRESS_STEP, client, context.getSourceAuthoritiesIndex(), context.getTargetAuthoritiesIndex()),
                    new ReindexMigrationJob(MigrationStep.REINDEX_TRANSACTIONS_INDEX_PROGRESS_STEP, client, context.getSourceTrackerStateIndex(), context.getTargetTrackerStateIndex()),
                    new CallbackMigrationJob(client, context.getMigrationCallbacks()),
                    new DocumentsMigrationJob(adminService, context.getMigrationTrackerStateIndex(), trackerRegistry, context.getMigrationTracker(), statusIndexServiceFactory, trackerExecutorFactory),
                    new CompleteMigrationJob()
            );

            validateMigrationJobs();
        }

        private void validateMigrationJobs() {
            long count = jobs.stream().map(MigrationJob::getMigrationStep).distinct().count();
            if (count != jobs.size()) {
                throw new MigrationException("MigrationJobs must have unique MigrationStep");
            }

            jobs.stream()
                    .filter(job -> job.getMigrationStep() == MigrationStep.COMPLETED_PROGRESS_STEP)
                    .findFirst()
                    .orElseThrow(() -> new MigrationException("MigrationJob with MigrationStep COMPLETED_PROGRESS_STEP not found"));
        }

        private void setMigrationContentDelegate(String content) {
            migrationState.setProgressContent(content);
            setMigrationState(context.getToVersion(), migrationState);
        }

        private String getMigrationContentDelegate() {
            return migrationState.getProgressContent();
        }

        public void run() {

            migrationState = getMigrationState(context.getToVersion());

            MigrationStep migrationStep = MigrationStep.valueOf(migrationState.getProgressStep());
            int startIndex = IntStream.of(0, jobs.size() - 1)
                    .filter(i -> jobs.get(i).getMigrationStep() == migrationStep)
                    .findFirst()
                    .orElseThrow(() -> new MigrationException("MigrationStep " + migrationStep + " not found in MigrationJobs"));


            for (int i = startIndex; i < jobs.size(); i++) {
                MigrationJob migrationJob = jobs.get(i);

                migrationState.setProgressStep(migrationJob.getMigrationStep().value);
                migrationState.setStatusMessage(migrationJob.getMigrationStep().message);
                setMigrationState(context.getToVersion(), migrationState);

                log.info("Start MigrationJob: {}", migrationJob.getMigrationStep());
                migrationJob.onEnterState(context);

                log.info("Run MigrationJob: {}", migrationJob.getMigrationStep());
                migrationJob.onProgressState(context);

                log.info("Finish MigrationJob: {}", migrationJob.getMigrationStep());
                migrationJob.onExitState(context);

                migrationState = new MigrationState();
            }
        }
    }
}

