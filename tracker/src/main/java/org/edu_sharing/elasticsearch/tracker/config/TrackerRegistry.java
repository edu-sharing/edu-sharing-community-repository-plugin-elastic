package org.edu_sharing.elasticsearch.tracker.config;

import io.micrometer.common.util.StringUtils;
import org.edu_sharing.elasticsearch.elasticsearch.core.*;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.AppInfo;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.metric.MetricContextHolder;
import org.edu_sharing.elasticsearch.tracker.TrackerServiceFactory;
import org.edu_sharing.elasticsearch.tracker.TransactionTracker;
import org.edu_sharing.elasticsearch.tracker.strategy.DependentStatusIndexServiceStrategie;
import org.edu_sharing.elasticsearch.tracker.strategy.FixNumberOfTransactionStrategy;
import org.edu_sharing.elasticsearch.tracker.strategy.TrackerStrategy;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

@Component
public class TrackerRegistry {

    private final TrackerServiceFactory trackerServiceFactory;
    private final StatusIndexServiceFactory statusIndexServiceFactory;
    private final Map<String, TrackerProvider<?>> trackerProviders;
    private final TrackerProperties props;
    private final AdminService adminService;
    private final StatusIndexService<AppInfo> appInfoStatusService;
    private final TransactionTracker mainTransactionTracker;


    private final Map<String, TransactionTracker> registeredTracker = new HashMap<>();


    public Map<String, TransactionTracker> getRegisteredTracker() {
        return Collections.unmodifiableMap(registeredTracker);
    }

    public TrackerRegistry(
            TrackerServiceFactory trackerServiceFactory,
            StatusIndexServiceFactory statusIndexServiceFactory,
            Map<String, TrackerProvider<?>> trackerProviders,
            TrackerProperties props,
            AdminService adminService,
            StatusIndexService<AppInfo> appInfoStatusService,
            TransactionTracker mainTransactionTracker
    ) {
        this.trackerServiceFactory = trackerServiceFactory;
        this.statusIndexServiceFactory = statusIndexServiceFactory;
        this.trackerProviders = trackerProviders;
        this.props = props;
        this.adminService = adminService;
        this.appInfoStatusService = appInfoStatusService;
        this.mainTransactionTracker = mainTransactionTracker;
        try {
            createTracker();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void createTracker() throws IOException {

        String appVersion = appInfoStatusService.getState().getTrackerVersion();

        props.getTracker().forEach((trackerName, cfg) -> {
            TransactionTracker trackerService;
            if (cfg.getProvider().equals("main")) {
                trackerService = mainTransactionTracker;
            } else {
                trackerService = createTransactionTracker(trackerName, cfg, appVersion);
            }

            trackerService.setNumberOfTransactions(cfg.getTransactions());
            if (!cfg.getIncludeNodeTypes().isEmpty()) {
                trackerService.setIncludeNodeTypes(cfg.getIncludeNodeTypes());
            }
            if (!cfg.getExcludeNodeTypes().isEmpty()) {
                trackerService.setExcludeNodeTypes(cfg.getExcludeNodeTypes());
            }
            if (!cfg.getIncludeAspects().isEmpty()) {
                trackerService.setIncludeAspects(cfg.getIncludeAspects());
            }
            if (!cfg.getExcludeAspects().isEmpty()) {
                trackerService.setExcludeAspects(cfg.getExcludeAspects());
            }
            if (Objects.nonNull(cfg.getTimeStep())) {
                trackerService.setTimeStep(cfg.getTimeStep().toMillis());
            }
            if (StringUtils.isNotBlank(cfg.getStoreIdentifier())) {
                trackerService.setStoreIdentifier(cfg.getStoreIdentifier());
            }
            if (StringUtils.isNotBlank(cfg.getStoreProtocol())) {
                trackerService.setStoreProtocol(cfg.getStoreProtocol());
            }
            registeredTracker.put(trackerName, trackerService);
        });
    }

    private TransactionTracker createTransactionTracker(String trackerName, TrackerProperties.TrackerConfig cfg, String appVersion) {
        TrackerProvider<?> trackerProvider = trackerProviders.get(cfg.getProvider());
        if (trackerProvider == null) {
            throw new RuntimeException("No tracker provider for trackerName: " + cfg.getProvider());
        }

        return trackerServiceFactory.createTrackerService(
                trackerProvider,
                createTransactionStateService(trackerName, appVersion),
                createTrackerStrategy(trackerName, cfg),
                createMetricContext(trackerName));
    }

    private StatusIndexService<Tx> createTransactionStateService(String trackerName, String appVersion) {
        String indexName = "transactions_" + trackerName + "_" + appVersion;
        IndexConfiguration indexConfiguration = new IndexConfiguration(req -> req.index(indexName));
        try {
            adminService.createIndex(indexConfiguration);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return statusIndexServiceFactory.createTransactionStateService(indexConfiguration.getIndex());
    }

    @NotNull
    private TrackerStrategy createTrackerStrategy(String trackerName, TrackerProperties.TrackerConfig cfg) {
        TrackerStrategy strategy = new FixNumberOfTransactionStrategy();
        if (!cfg.getTrackerDependency().isEmpty()) {
            // well dependent tracker needs to be initialized first, but this will also prevent cyclical dependencies
            List<StatusIndexServiceInterface<Tx>> dependentIndices = cfg.getTrackerDependency()
                    .stream()
                    .map(dep -> {
                        TransactionTracker transactionTracker = registeredTracker.get(dep);
                        if (transactionTracker == null) {
                            throw new RuntimeException("tracker " + dep + " not found in registry. please add " + trackerName + " in config after " + cfg.getTrackerDependency());
                        }
                        return transactionTracker.getTransactionStateService();
                    })
                    .toList();
            strategy = new DependentStatusIndexServiceStrategie(dependentIndices);
        }
        return strategy;
    }

    private static MetricContextHolder.MetricContext createMetricContext(String trackerName) {
        return MetricContextHolder.MetricContext.builder()
                .labelProgress(trackerName + "Progress")
                .descriptionProgress(trackerName.toUpperCase() + "progress")
                .labelDelay(trackerName + "Delay")
                .descriptionDelay(trackerName.toUpperCase() + " Delay in seconds")
                .build();
    }
}
