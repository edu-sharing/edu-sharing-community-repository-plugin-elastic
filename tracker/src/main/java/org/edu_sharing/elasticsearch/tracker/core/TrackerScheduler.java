package org.edu_sharing.elasticsearch.tracker.core;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.tracker.core.config.TrackerScheduleProperties;
import org.edu_sharing.elasticsearch.tracker.core.config.TrackerSchedulerSettings;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Order(0)
@RequiredArgsConstructor
public class TrackerScheduler implements SmartInitializingSingleton {

    private final IndexConfiguration trackerStateIndex;
    private final TrackerRegistry trackerRegistry;
    private final TrackerExecutorFactory trackerExecutorFactory;

    private final Map<String, TaskScheduler> executors = new HashMap<>();

    @Override
    public void afterSingletonsInstantiated() {

        Set<TrackerConfig<?, ?>> activeTrackerConfigs = trackerRegistry.getActiveTrackerConfigs();
        Map<TrackerConfig<?, ?>, TrackingExecutor<?>> trackerExecutors = trackerExecutorFactory.createTrackerExecutors(activeTrackerConfigs, trackerStateIndex.getIndex());
        trackerExecutors.forEach((trackerConfig, trackingExecutor) -> {
            TaskScheduler taskScheduler = scheduleTracker(trackerConfig, trackingExecutor);
            String schedulerName = getSchedulerName(trackerConfig);
            executors.putIfAbsent(schedulerName, taskScheduler);
        });

        List<TrackerCoroutineConfig> activeTrackerCoroutineConfigs = trackerRegistry.getActiveTrackerCoroutineConfigs();
        Map<TrackerCoroutineConfig, TrackingExecutor<?>> coroutineTrackerExecutors = trackerExecutorFactory.createTrackerExecutor(activeTrackerCoroutineConfigs);
        coroutineTrackerExecutors.forEach((trackerCoroutineConfig, trackingExecutor) -> {
            TaskScheduler taskScheduler = scheduleTracker(trackerCoroutineConfig, trackingExecutor);
            String schedulerName = getSchedulerName(trackerCoroutineConfig);
            executors.putIfAbsent(schedulerName, taskScheduler);
        });
    }

    @NotNull
    private static String getSchedulerName(TrackerScheduleConfig<?,?> trackerCoroutineConfig) {
        return Objects.requireNonNullElse(trackerCoroutineConfig.getConfig().getScheduler().getSchedulerName(), trackerCoroutineConfig.getName());
    }

    private TaskScheduler scheduleTracker(TrackerScheduleConfig<?,?> trackerScheduleConfig, TrackingExecutor<?> trackingExecutor) {
        TrackerScheduleProperties config = trackerScheduleConfig.getConfig();

        String schedulerName = getSchedulerName(trackerScheduleConfig);
        TaskScheduler taskScheduler = executors.get(schedulerName);

        if(taskScheduler == null) {
            taskScheduler = new ConcurrentTaskScheduler(Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r);
                t.setName(schedulerName); // Threadname aus Config-Key
                t.setDaemon(true);
                return t;
            }));
        }

        TrackerSchedulerSettings schedulerConfig = config.getScheduler();
        if (schedulerConfig.getCron() != null) {
            taskScheduler.schedule(trackingExecutor::track, new CronTrigger(schedulerConfig.getCron()));
        } else {
            taskScheduler.scheduleWithFixedDelay(trackingExecutor::track,
                    Instant.now().plusMillis(schedulerConfig.getDelay().toMillis()),
                    schedulerConfig.getInterval());
        }
        return taskScheduler;
    }


    @PreDestroy
    public void shutdown() {
        executors.values().forEach(executor -> {
            if (executor instanceof ConcurrentTaskScheduler) {
                ScheduledExecutorService scheduledExecutor =
                        (ScheduledExecutorService) ((ConcurrentTaskScheduler) executor).getConcurrentExecutor();
                scheduledExecutor.shutdown();
            }
        });
    }
}
