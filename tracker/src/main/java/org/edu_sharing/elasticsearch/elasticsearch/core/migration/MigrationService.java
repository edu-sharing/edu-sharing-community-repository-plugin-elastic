package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.tasks.GetTasksResponse;
import co.elastic.clients.elasticsearch.tasks.TaskInfo;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.TrackerAvailabilityTickService;
import org.edu_sharing.elasticsearch.elasticsearch.core.AdminService;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceFactory;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.AppInfo;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.tracker.*;
import org.edu_sharing.elasticsearch.tracker.strategy.MaxCommitTimeStrategy;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Component
@RequiredArgsConstructor
@DependsOn("adminService")
public class MigrationService {
    private final AdminService adminService;
    private final IndexConfiguration migrationIndex;
    private final ElasticsearchClient client;
    private final StatusIndexService<AppInfo> appInfoStatusService;
    private final StatusIndexServiceFactory statusIndexServiceFactory;
    private final TrackerServiceFactory trackerServiceFactory;
    private final StatusIndexService<Tx> transactionStateService;
    private final List<MigrationInfo> migrationInfos;
    private final TrackerAvailabilityTickService trackerAvailabilityTickService;
    private final Map<String, TransactionTrackerBase> trackerRegistry;
    private final TransactionTracker transactionTracker;


    @Value("${migration.authorities.transactions.max:5000}")
    int authoritiesTrackerNumberOfTransactions;

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

        boolean requiresDocMigration = IntStream.range(startIndex, migrationInfos.size())
                .mapToObj(migrationInfos::get)
                .anyMatch(MigrationInfo::isRequiresReindex);

        boolean requiresAuthoritiesMigration = IntStream.range(startIndex, migrationInfos.size())
                .mapToObj(migrationInfos::get)
                .anyMatch(MigrationInfo::isRequiresAuthoritiesReindex);

        Set<String> knownMigrationCallbacks = new HashSet<>();
        migrationInfos.stream()
                .map(MigrationInfo::getCallback)
                .filter(Objects::nonNull)
                .forEach(callback -> {
                    if(knownMigrationCallbacks.contains(callback.getName())){
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
        String sourceTransactionIndex = currentVersion == null ? "transactions" : "transactions_" + currentVersion;
        String sourceAuthoritiesIndex = currentVersion == null ? "authorities" : "authorities_" + currentVersion;

        if (adminService.indicesExists(sourceWorkspaceIndex, sourceTransactionIndex)) {

            while (!adminService.indecesConfiguredExist()) {
                log.info("waiting for indeces...");
                Thread.sleep(2000);
            }

            // we need to migrate
            MigrationJobImpl migrationJob = new MigrationJobImpl(
                    sourceWorkspaceIndex,
                    sourceTransactionIndex,
                    sourceAuthoritiesIndex,
                    latestVersion,
                    migrationIndex.getIndex(),
                    requiresDocMigration,
                    requiresAuthoritiesMigration,
                    migrationCallbacks);

            migrationJob.run();
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

    private MigrationState getMigrationState(String version) throws IOException {
        return client.search(req -> req
                                .index(migrationIndex.getIndex())
                                .query(q -> q.ids(id -> id.values(version)))
                        , MigrationState.class)
                .hits()
                .hits()
                .stream()
                .filter(x -> Objects.nonNull(x.source()))
                .map(Hit::source)
                .findFirst()
                .orElse(new MigrationState());
    }

    @lombok.Value
    class MigrationJobImpl implements MigrationJob {

        String sourceWorkspaceIndex;
        String sourceTransactionIndex;
        String sourceAuthoritiesIndex;
        String version;
        String index;
        boolean requiresDocumentMigration;
        boolean requiresAuthoritiesMigration;

        String migrationTransactionIndex;
        String migrationTransactionAuthoritiesIndex;
        List<MigrationCallback> migrationCallbacks;

        MigrationJobImpl(String sourceWorkspaceIndex, String sourceTransactionIndex, String sourceAuthoritiesIndex, String version, String index, boolean requiresDocumentMigration, boolean requiresAuthoritiesMigration, List<MigrationCallback> migrationCallbacks) {
            this.sourceWorkspaceIndex = sourceWorkspaceIndex;
            this.sourceTransactionIndex = sourceTransactionIndex;
            this.sourceAuthoritiesIndex = sourceAuthoritiesIndex;
            this.version = version;
            this.index = index;
            this.requiresDocumentMigration = requiresDocumentMigration;
            this.requiresAuthoritiesMigration = requiresAuthoritiesMigration;
            migrationTransactionIndex = "migration_" + version + "_tracker";
            migrationTransactionAuthoritiesIndex = "migration_authorities_" + version + "_tracker";
            this.migrationCallbacks = migrationCallbacks;
        }


        public void run() throws IOException, InterruptedException {
            MigrationState migrationState = getMigrationState(version);

            MigrationStep curStep = MigrationStep.valueOf(migrationState.getProgressStep());
            while (true) {
                trackerAvailabilityTickService.tick();
                switch (curStep) {
                    case INIT_PROGRESS_STEP: {
                        log.info("start migration");
                        log.info("start reindex workspace");
                        String taskId = reindex(sourceWorkspaceIndex, "workspace_" + version);
                        curStep = MigrationStep.REINDEX_WORKSPACE_INDEX_PROGRESS_STEP;
                        updateMigrationState(migrationState, curStep, taskId);
                        break;
                    }

                    case REINDEX_WORKSPACE_INDEX_PROGRESS_STEP: {
                        GetTasksResponse tasksResponse = client.tasks().get(req -> req.taskId(migrationState.getProgressContent()));

                        TaskInfo task = tasksResponse.task();
                        if (tasksResponse.error() != null) {
                            throw new MigrationException(String.format("Task failed: %s", task));
                        }

                        if (Boolean.TRUE.equals(task.cancelled())) {
                            throw new MigrationException(String.format("Task was cancelled: %s", task));
                        }

                        if (tasksResponse.completed()) {
                            log.info("reindexing workspace finished: {}", task);
                            String taskId = reindex(sourceTransactionIndex, "transactions_" + version);
                            curStep = MigrationStep.REINDEX_TRANSACTIONS_INDEX_PROGRESS_STEP;
                            updateMigrationState(migrationState, curStep, taskId);
                            break;
                        }

                        log.info("reindexing workspace...");
                        log.info("Task progress: {}", task);
                        Thread.sleep(5000);
                        break;
                    }

                    case REINDEX_TRANSACTIONS_INDEX_PROGRESS_STEP: {
                        GetTasksResponse tasksResponse = client.tasks().get(req -> req.taskId(migrationState.getProgressContent()));

                        TaskInfo task = tasksResponse.task();
                        if (tasksResponse.error() != null) {
                            throw new MigrationException(String.format("Task %s:%s failed with: %s", task.node(), task.id(), tasksResponse.error().reason()));
                        }
                        if (tasksResponse.response() != null) {
                            // failures array is not mapped to the model for some strange reason
                            JsonObject json = tasksResponse.response().toJson().asJsonObject();
                            if (json.containsKey("failures")) {
                                JsonArray array = json.getJsonArray("failures");
                                if (!array.isEmpty()) {
                                    throw new MigrationException(String.format("Task %s:%s failed with: %s", task.node(), task.id(), array.toString()));
                                }
                            }
                        }

                        if (Boolean.TRUE.equals(task.cancelled())) {
                            throw new MigrationException(String.format("Task %s:%s was cancelled", task.node(), task.id()));
                        }


                        if (tasksResponse.completed()) {
                            log.info("reindexing transactions finished: {}", task);
                            BooleanResponse exists = client.indices().exists(e -> e.index(sourceAuthoritiesIndex));
                            if (!exists.value()) client.indices().create(c -> c.index(sourceAuthoritiesIndex));
                            String taskId = reindex(sourceTransactionIndex, "authorities_" + version);
                            curStep = MigrationStep.REINDEX_AUTHORITIES_INDEX_PROGRESS_STEP;
                            updateMigrationState(migrationState, curStep, taskId);
                            break;
                        }

                        log.info("reindexing transactions...");
                        log.info("Task progress: {}", task);
                        Thread.sleep(5000);
                        break;
                    }
                    case REINDEX_AUTHORITIES_INDEX_PROGRESS_STEP: {
                        GetTasksResponse tasksResponse = client.tasks().get(req -> req.taskId(migrationState.getProgressContent()));

                        TaskInfo task = tasksResponse.task();
                        if (tasksResponse.error() != null) {
                            throw new MigrationException(String.format("Task %s:%s failed with: %s", task.node(), task.id(), tasksResponse.error().reason()));
                        }
                        if (tasksResponse.response() != null) {
                            // failures array is not mapped to the model for some strange reason
                            JsonObject json = tasksResponse.response().toJson().asJsonObject();
                            if (json.containsKey("failures")) {
                                JsonArray array = json.getJsonArray("failures");
                                if (!array.isEmpty()) {
                                    throw new MigrationException(String.format("Task %s:%s failed with: %s", task.node(), task.id(), array.toString()));
                                }
                            }
                        }

                        if (Boolean.TRUE.equals(task.cancelled())) {
                            throw new MigrationException(String.format("Task %s:%s was cancelled", task.node(), task.id()));
                        }

                        if (tasksResponse.completed()) {
                            log.info("reindexing authorities finished: {}", task);
                            if (requiresAuthoritiesMigration) {
                                log.info("create authorities migration transactions index");
                                IndexConfiguration indexConfiguration = new IndexConfiguration(req -> req.index(migrationTransactionAuthoritiesIndex));
                                adminService.createIndex(indexConfiguration);

                                long txnCommitTime = transactionStateService.getState().getTxnCommitTime();

                                curStep = MigrationStep.MIGRATE_AUTHORITIES_INDEX_PROGRESS_STEP;
                                migrationState.setProgressStep(curStep.value);
                                migrationState.setStatusMessage(curStep.message);
                                updateMigrationState(migrationState, curStep, Long.toString(txnCommitTime));
                                log.info("start migration of authorities");
                            } else {
                                migrationState.setProgressContent(null);
                                curStep = setInitialStateMigrationCallback(migrationState);
                            }
                            break;
                        }

                        log.info("reindexing authorities...");
                        log.info("Task progress: {}", task);
                        Thread.sleep(5000);
                        break;

                    }

                    case MIGRATE_AUTHORITIES_INDEX_PROGRESS_STEP: {
                        long maxCommitTime = Long.parseLong(migrationState.getProgressContent());
                        IndexConfiguration indexConfiguration = new IndexConfiguration(req -> req.index(migrationTransactionAuthoritiesIndex));
                        StatusIndexService<Tx> migrationTransactionStateService = statusIndexServiceFactory.createTransactionStateService(indexConfiguration.getIndex());
                        AuthoritiesTracker migrationTracker = trackerServiceFactory.createTrackerService(AuthoritiesTracker::new, migrationTransactionStateService, new MaxCommitTimeStrategy(maxCommitTime), null);
                        migrationTracker.setNumberOfTransactions(authoritiesTrackerNumberOfTransactions);
                        do {
                            trackerAvailabilityTickService.tick();
                        } while (migrationTracker.track() != TransactionTracker.State.FINISHED);

                        log.info("delete authorities migration transactions index");
                        adminService.deleteIndex(indexConfiguration);

                        migrationState.setProgressContent(null);
                        curStep = setInitialStateMigrationCallback(migrationState);
                        break;
                    }

                    case ON_MIGRATION_CALLBACK_PROGRESS_STEP:
                        String migrationCallbackProgressContent = migrationState.getProgressContent();
                        int startIndex = 0;
                        if (migrationCallbackProgressContent != null) {
                            String migrationCallbackName = migrationCallbackProgressContent.split(":")[0];
                            startIndex = IntStream.range(0, migrationCallbacks.size())
                                    .filter(i -> migrationCallbacks.get(i).getName().equals(migrationCallbackName))
                                    .findFirst()
                                    .orElseThrow(() -> new IllegalStateException("No migration callback found with name " + migrationCallbackName + " in " + migrationCallbacks.stream().map(MigrationCallback::getName).collect(Collectors.joining(","))));
                        }

                        IntStream.range(startIndex, migrationCallbacks.size())
                                .mapToObj(migrationCallbacks::get)
                                .filter(Objects::nonNull)
                                .forEach(callback -> {
                                    log.info("Run Migration callback {}: {}", callback.getName(), callback.getClass().getSimpleName());
                                    callback.onMigrationCallback(this, migrationState, client, trackerRegistry, transactionTracker);
                                });

                        if (requiresDocumentMigration) {
                            curStep = setStateMigrateDocs(migrationState);
                        } else {
                            curStep = setStateComplete(migrationState);
                            log.info("document migration finished");
                            log.info("migration completed");
                        }
                        break;

                    case MIGRATE_DOCUMENTS_PROGRESS_STEP:
                        long maxCommitTime = Long.parseLong(migrationState.getProgressContent());
                        IndexConfiguration indexConfiguration = new IndexConfiguration(req -> req.index(migrationTransactionIndex));
                        StatusIndexService<Tx> migrationTransactionStateService = statusIndexServiceFactory.createTransactionStateService(indexConfiguration.getIndex());
                        DefaultTransactionTracker migrationTracker = trackerServiceFactory.createDefaultTrackerService(migrationTransactionStateService, new MaxCommitTimeStrategy(maxCommitTime));

                        do {
                            trackerAvailabilityTickService.tick();
                        } while (migrationTracker.track() != TransactionTracker.State.FINISHED);

                        log.info("delete document migration transactions index");
                        adminService.deleteIndex(indexConfiguration);

                        curStep = setStateComplete(migrationState);
                        log.info("document migration finished");
                        log.info("migration completed");
                        break;

                    case COMPLETED_PROGRESS_STEP:
                        log.info("migration completed, nothing to do.");
                        return;
                }
            }
        }

        private MigrationStep setInitialStateMigrationCallback(MigrationState migrationState) throws IOException {
            MigrationStep curStep = MigrationStep.ON_MIGRATION_CALLBACK_PROGRESS_STEP;
            migrationState.setProgressStep(curStep.value);
            migrationState.setStatusMessage(curStep.message + " - started");
            migrationState.setProgressContent(null);
            updateMigrationState(migrationState, curStep, null);
            return curStep;
        }

        @Override
        public MigrationStep setStateMigrationCallback(MigrationState migrationState, MigrationCallback migrationCallback, String progressContent, String message) throws IOException {
            MigrationStep curStep = MigrationStep.ON_MIGRATION_CALLBACK_PROGRESS_STEP;
            migrationState.setProgressStep(curStep.value);
            migrationState.setStatusMessage(curStep.message + " - " + message);
            migrationState.setProgressContent(migrationCallback.getName() + ":" + progressContent);
            updateMigrationState(migrationState, curStep, null);
            return curStep;
        }

        @Override
        public String getProgressContentFromStateMigrationCallback(MigrationState migrationState) {
            String progressContent = migrationState.getProgressContent();
            if (progressContent == null) {
                return null;
            }

            return progressContent.split(":")[1];
        }

        @NotNull
        private MigrationStep setStateMigrateDocs(MigrationState migrationState) throws IOException {
            log.info("create document migration transactions index");
            IndexConfiguration indexConfiguration = new IndexConfiguration(req -> req.index(migrationTransactionIndex));
            adminService.createIndex(indexConfiguration);
            long txnCommitTime = transactionStateService.getState().getTxnCommitTime();

            MigrationStep curStep = MigrationStep.MIGRATE_DOCUMENTS_PROGRESS_STEP;
            migrationState.setProgressStep(curStep.value);
            migrationState.setStatusMessage(curStep.message);
            updateMigrationState(migrationState, curStep, Long.toString(txnCommitTime));
            log.info("start migration of documents");
            return curStep;
        }

        @NotNull
        private MigrationStep setStateComplete(MigrationState migrationState) throws IOException {
            MigrationStep curStep = MigrationStep.COMPLETED_PROGRESS_STEP;
            migrationState.setProgressStep(curStep.value);
            migrationState.setStatusMessage(curStep.message);
            updateMigrationState(migrationState, curStep, null);
            return curStep;
        }

        String reindex(String sourceIndex, String targetIndex) throws IOException {
            String task = client.reindex(req -> req
                            .waitForCompletion(false)
                            .conflicts(Conflicts.Proceed)
                            .source(src -> src.index(sourceIndex))
                            .dest(dest -> dest.index(targetIndex)))
                    .task();

            log.info("Reindex: from {} to {}, task {}", sourceIndex, targetIndex, task);
            return task;
        }

        private void updateMigrationState(MigrationState migrationState, MigrationStep migrationStep, String content) throws IOException {
            migrationState.setUpdateDate(new Date());
            migrationState.setProgressStep(migrationStep.value);
            migrationState.setStatusMessage(migrationStep.message);
            migrationState.setProgressContent(content);

            log.info("Update MigrationState: {}", migrationState);
            client.index(req -> req
                    .index(index)
                    .id(version)
                    .document(migrationState));
        }
    }
}

