package org.edu_sharing.elasticsearch.tracker.core;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.tracker.core.config.BaseTrackerProperties;
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

            BaseTrackerProperties config = trackerConfig.getConfig();
            TaskScheduler taskScheduler = new ConcurrentTaskScheduler(Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r);
                t.setName(trackerConfig.getName()); // Threadname aus Config-Key
                t.setDaemon(true);
                return t;
            }));

            executors.add(taskScheduler);

            TrackerSchedulerSettings schedulerConfig = config.getScheduler();
            if (schedulerConfig.getCron() != null) {
                taskScheduler.schedule(trackingExecutor::track, new CronTrigger(schedulerConfig.getCron()));
            } else {
                taskScheduler.scheduleWithFixedDelay(trackingExecutor::track,
                        Instant.now().plusMillis(schedulerConfig.getDelay().toMillis()),
                        schedulerConfig.getInterval());
            }
        });
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
