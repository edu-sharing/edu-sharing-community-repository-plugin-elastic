package org.edu_sharing.elasticsearch.tracker.core;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.tracker.core.config.TrackerScheduleProperties;
import org.edu_sharing.elasticsearch.tracker.core.config.TrackerSchedulerSettings;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Order(0)
@RequiredArgsConstructor
public class TrackerScheduler implements SmartInitializingSingleton {

    private final IndexConfiguration trackerStateIndex;
    private final TrackerRegistry trackerRegistry;
    private final TrackerExecutorFactory trackerExecutorFactory;

    private final List<TaskScheduler> executors = new ArrayList<>();

    @Override
    public void afterSingletonsInstantiated() {

        Set<TrackerConfig<?, ?>> activeTrackerConfigs = trackerRegistry.getActiveTrackerConfigs();
        Map<TrackerConfig<?, ?>, TrackingExecutor<?>> trackerExecutors = trackerExecutorFactory.createTrackerExecutors(activeTrackerConfigs, trackerStateIndex.getIndex());
        trackerExecutors.forEach((trackerConfig, trackingExecutor) -> {
            TaskScheduler taskScheduler = scheduleTracker(trackerConfig, trackingExecutor);
            executors.add(taskScheduler);
        });

        List<TrackerCoroutineConfig> activeTrackerCoroutineConfigs = trackerRegistry.getActiveTrackerCoroutineConfigs();
        Map<TrackerCoroutineConfig, TrackingExecutor<?>> coroutineTrackerExecutors = trackerExecutorFactory.createTrackerExecutor(activeTrackerCoroutineConfigs);
        coroutineTrackerExecutors.forEach((trackerCoroutineConfig, trackingExecutor) -> {
            TaskScheduler taskScheduler = scheduleTracker(trackerCoroutineConfig, trackingExecutor);
            executors.add(taskScheduler);
        });
    }

    private TaskScheduler scheduleTracker(TrackerScheduleConfig<?,?> trackerScheduleConfig, TrackingExecutor<?> trackingExecutor) {
        TrackerScheduleProperties config = trackerScheduleConfig.getConfig();
        TaskScheduler taskScheduler = new ConcurrentTaskScheduler(Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName(trackerScheduleConfig.getName()); // Threadname aus Config-Key
            t.setDaemon(true);
            return t;
        }));


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
        executors.forEach(executor -> {
            if (executor instanceof ConcurrentTaskScheduler) {
                ScheduledExecutorService scheduledExecutor =
                        (ScheduledExecutorService) ((ConcurrentTaskScheduler) executor).getConcurrentExecutor();
                scheduledExecutor.shutdown();
            }
        });
    }
}
