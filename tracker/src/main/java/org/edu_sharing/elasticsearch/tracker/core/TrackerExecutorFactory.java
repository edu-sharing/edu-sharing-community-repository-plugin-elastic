package org.edu_sharing.elasticsearch.tracker.core;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceFactory;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceInterface;
import org.edu_sharing.elasticsearch.metric.MetricContextHolder;
import org.edu_sharing.elasticsearch.tracker.strategy.CommiteTimeStatus;
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
            if(stateStatusIndexServiceInterface == null){
                throw new IllegalStateException("No index found for tracker " + trackerConfig.getName());
            }
            return (StatusIndexServiceInterface<STATE>) stateStatusIndexServiceInterface;
        }

        @SuppressWarnings("unchecked")
        public StatusIndexServiceInterface<? extends CommiteTimeStatus> getCommitTimeStatusIndex(String name) {
            StatusIndexServiceInterface<?> statusIndexServiceInterface = indexMap.get(name);
            if (statusIndexServiceInterface == null) {
                throw new IllegalStateException("No index found for tracker " + name);
            }

            if (CommiteTimeStatus.class.isAssignableFrom(statusIndexServiceInterface.getClass())) {
                throw new IllegalStateException("The requested tracker config does not use a CommiteTimeStatus index: " + name);
            }

            return (StatusIndexServiceInterface<? extends CommiteTimeStatus>) statusIndexServiceInterface;
        }
    }

    public Map<TrackerConfig<?, ?>, TrackingExecutor<?>> createTrackerExecutors(Collection<TrackerConfig<?, ?>> trackerConfigs, String index) {
        return createTrackerExecutors(trackerConfigs, index, this::applyDefaultStrategy);
    }

    public Map<TrackerConfig<?, ?>, TrackingExecutor<?>> createTrackerExecutors(Collection<TrackerConfig<?, ?>> trackerConfigs, String index, Function<TrackerConfig<?,?>, TrackerStrategy> defaultStrategySupplier) {
        StatusIndexServiceRegistry statusIndexServiceRegistry = new StatusIndexServiceRegistry(index);

        Map<TrackerConfig<?, ?>, TrackingExecutor<?>> result = new LinkedHashMap<>();
        for (TrackerConfig<?, ?> trackerConfig : trackerConfigs) {
            TrackingExecutor<?> trackingExecutor = createTrackerExecutor(trackerConfig, statusIndexServiceRegistry, defaultStrategySupplier);
            result.put(trackerConfig, trackingExecutor);
        }
        return result;
    }

    private <STATUS> TrackingExecutor<STATUS> createTrackerExecutor(TrackerConfig<?, STATUS> trackerConfig, StatusIndexServiceRegistry statusIndexServiceRegistry, Function<TrackerConfig<?,?>, TrackerStrategy> defaultStrategySupplier) {
        return createTrackerExecutor(
                trackerConfig.getTracker(),
                createTrackingContext(trackerConfig, statusIndexServiceRegistry, defaultStrategySupplier));
    }


    private <STATE> TrackingContext<STATE> createTrackingContext(TrackerConfig<?, STATE> trackerConfig,StatusIndexServiceRegistry statusIndexServiceRegistry, Function<TrackerConfig<?, ?>, TrackerStrategy> defaultStrategySupplier) {
        List<String> trackerNames = trackerConfig.getConfig().getDependsOn();
        TrackerStrategy trackerStrategy;
        if (!trackerNames.isEmpty()) {
            List<StatusIndexServiceInterface<? extends CommiteTimeStatus>> dependentTransactionStateServices = new ArrayList<>();
            for (String trackerName : trackerNames) {
                StatusIndexServiceInterface<? extends CommiteTimeStatus> statusIndexServiceInterface = statusIndexServiceRegistry.getCommitTimeStatusIndex(trackerName);
                dependentTransactionStateServices.add(statusIndexServiceInterface);
            }
            trackerStrategy = new DependentStatusIndexServiceStrategie(dependentTransactionStateServices);
        } else {
            trackerStrategy = defaultStrategySupplier.apply(trackerConfig);
        }


        return new TrackingContext<>(trackerStrategy,
                statusIndexServiceRegistry.getCommitTimeStatusIndex(trackerConfig),
                MetricContextHolder.MetricContext.builder()
                        .labelProgress(trackerConfig.getName() + "Progress")
                        .descriptionProgress(trackerConfig.getName().toUpperCase() + "progress")
                        .labelDelay(trackerConfig.getName() + "Delay")
                        .descriptionDelay(trackerConfig.getName().toUpperCase() + " Delay in seconds")
                        .build());
    }

    @Bean
    @Scope("prototype")
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public <
            StATUS> TrackingExecutor<StATUS> createTrackerExecutor(Tracker<StATUS> tracker, TrackingContext<StATUS> trackingContext) {
        return new TrackingExecutor<>(tracker, trackingContext);
    }
}
