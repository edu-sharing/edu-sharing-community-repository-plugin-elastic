package org.edu_sharing.elasticsearch.tracker.core;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.TrackerAvailabilityTickService;
import org.edu_sharing.elasticsearch.elasticsearch.core.ApplicationState;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceFactory;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceInterface;
import org.edu_sharing.elasticsearch.metric.MetricContextFactory;
import org.edu_sharing.elasticsearch.tracker.strategy.CommitTimeStatus;
import org.edu_sharing.elasticsearch.tracker.strategy.DependentStatusIndexServiceStrategie;
import org.edu_sharing.elasticsearch.tracker.strategy.FixNumberOfTransactionStrategy;
import org.edu_sharing.elasticsearch.tracker.strategy.TrackerStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration
@RequiredArgsConstructor
public class TrackerExecutorFactory {


    private final StatusIndexServiceFactory statusIndexServiceFactory;
    private final TrackerRegistry trackerRegistry;
    private final MetricContextFactory metricContextFactory;
    private final ApplicationState applicationState;
    private final TrackerAvailabilityTickService tickService;

    private TrackerStrategy applyDefaultStrategy(TrackerConfig<?, ?> trackerConfig) {
        return new FixNumberOfTransactionStrategy();
    }

    private final class StatusIndexServiceRegistry {
        Map<String, StatusIndexServiceInterface<?>> indexMap;

        public StatusIndexServiceRegistry(String index) {
            Set<TrackerConfig<?, ?>> fallbackTrackerConfigs = trackerRegistry.getActiveTrackerConfigs();
            indexMap = fallbackTrackerConfigs.stream()
                    .collect(Collectors.toMap(
                            TrackerConfig::getName,
                            x -> statusIndexServiceFactory.createStateService(x.getStatusClass(), x.getName(), index)));
        }

        @SuppressWarnings("unchecked")
        public <STATE> StatusIndexServiceInterface<STATE> getCommitTimeStatusIndex(TrackerConfig<?, STATE> trackerConfig) {
            StatusIndexServiceInterface<?> stateStatusIndexServiceInterface = indexMap.get(trackerConfig.getName());
            if (stateStatusIndexServiceInterface == null) {
                throw new IllegalStateException("No index found for tracker " + trackerConfig.getName());
            }
            return (StatusIndexServiceInterface<STATE>) stateStatusIndexServiceInterface;
        }

        @SuppressWarnings("unchecked")
        public StatusIndexServiceInterface<? extends CommitTimeStatus> getCommitTimeStatusIndex(String name) {
            StatusIndexServiceInterface<?> statusIndexServiceInterface = indexMap.get(name);
            if (statusIndexServiceInterface == null) {
                throw new IllegalStateException("No index found for tracker " + name);
            }

            if (CommitTimeStatus.class.isAssignableFrom(statusIndexServiceInterface.getClass())) {
                throw new IllegalStateException("The requested tracker config does not use a CommiteTimeStatus index: " + name);
            }

            return (StatusIndexServiceInterface<? extends CommitTimeStatus>) statusIndexServiceInterface;
        }
    }



    public Map<TrackerConfig<?, ?>, TrackingExecutor<?>> createTrackerExecutors(Collection<TrackerConfig<?, ?>> trackerConfigs, String index) {
        return createTrackerExecutors(trackerConfigs, index, this::applyDefaultStrategy);
    }

    public Map<TrackerConfig<?, ?>, TrackingExecutor<?>> createTrackerExecutors(Collection<TrackerConfig<?, ?>> trackerConfigs, String index, Function<TrackerConfig<?, ?>, TrackerStrategy> defaultStrategySupplier) {
        StatusIndexServiceRegistry statusIndexServiceRegistry = new StatusIndexServiceRegistry(index);

        Map<TrackerConfig<?, ?>, TrackingExecutor<?>> result = new LinkedHashMap<>();
        for (TrackerConfig<?, ?> trackerConfig : trackerConfigs) {
            TrackingExecutor<?> trackingExecutor = createTrackerExecutor(trackerConfig, statusIndexServiceRegistry, defaultStrategySupplier);
            result.put(trackerConfig, trackingExecutor);
        }
        return result;
    }

    public Map<TrackerCoroutineConfig, TrackingExecutor<?>> createTrackerExecutor(List<TrackerCoroutineConfig> tackerCoroutineConfigs) {
        Map<TrackerCoroutineConfig, TrackingExecutor<?>> result = new LinkedHashMap<>();
        for (TrackerCoroutineConfig tackerCoroutineConfig : tackerCoroutineConfigs) {
            TrackingExecutor<?> trackingExecutor = createTrackerExecutor(tackerCoroutineConfig.getTracker(),
                    new TrackingContext<>(tackerCoroutineConfig.getName(),
                    null,
                    null,
                    metricContextFactory.createMetric(tackerCoroutineConfig.getName(), tackerCoroutineConfig.getTracker().reportsProgress())) );
            result.put(tackerCoroutineConfig, trackingExecutor);
        }

        return result;
    }

    private <STATUS> TrackingExecutor<STATUS> createTrackerExecutor(TrackerConfig<?, STATUS> trackerConfig, StatusIndexServiceRegistry statusIndexServiceRegistry, Function<TrackerConfig<?, ?>, TrackerStrategy> defaultStrategySupplier) {
        return createTrackerExecutor(
                trackerConfig.getTracker(),
                createTrackingContext(trackerConfig, statusIndexServiceRegistry, defaultStrategySupplier));
    }


    private <STATE> TrackingContext<STATE> createTrackingContext(TrackerConfig<?, STATE> trackerConfig, StatusIndexServiceRegistry statusIndexServiceRegistry, Function<TrackerConfig<?, ?>, TrackerStrategy> defaultStrategySupplier) {
        List<String> trackerNames = trackerConfig.getConfig().getDependsOn();
        TrackerStrategy trackerStrategy;
        if (!trackerNames.isEmpty()) {
            List<StatusIndexServiceInterface<? extends CommitTimeStatus>> dependentTransactionStateServices = new ArrayList<>();
            for (String trackerName : trackerNames) {
                StatusIndexServiceInterface<? extends CommitTimeStatus> statusIndexServiceInterface = statusIndexServiceRegistry.getCommitTimeStatusIndex(trackerName);
                dependentTransactionStateServices.add(statusIndexServiceInterface);
            }
            trackerStrategy = new DependentStatusIndexServiceStrategie(dependentTransactionStateServices);
        } else {
            trackerStrategy = defaultStrategySupplier.apply(trackerConfig);
        }

        return new TrackingContext<>(trackerConfig.getName(),
                trackerStrategy,
                statusIndexServiceRegistry.getCommitTimeStatusIndex(trackerConfig),
                metricContextFactory.createMetric(trackerConfig.getName(), trackerConfig.getTracker().reportsProgress()));
    }


    @Bean
    @Scope("prototype")
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public <STATUS> TrackingExecutor<STATUS> createTrackerExecutor(Tracker<STATUS> tracker, TrackingContext<STATUS> trackingContext) {
        return new TrackingExecutor<>(tracker, trackingContext, applicationState, tickService);
    }
}
