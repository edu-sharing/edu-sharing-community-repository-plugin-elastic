package org.edu_sharing.elasticsearch.tracker.generic;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.tracker.TransactionTrackerBase;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class TrackerScheduler {

    private final Map<String, TransactionTrackerBase> trackerRegistry;
    private final TrackerProperties props;

    private final List<ScheduledExecutorService> executors = new ArrayList<>();

    @PostConstruct
    public void start() {
        trackerRegistry.forEach((key, tracker) -> {
            ScheduledExecutorService executor =
                    Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r);
                        t.setName(key); // Threadname aus Config-Key
                        t.setDaemon(true);
                        return t;
                    });

            executors.add(executor);

            long intervall = props.getTrackers().get(key).getInterval();
            if(intervall == 0){
                intervall = 5000;
            }
            executor.scheduleWithFixedDelay(
                    tracker::track,
                    0,
                    intervall,
                    TimeUnit.MILLISECONDS
            );
        });
    }

    @PreDestroy
    public void shutdown() {
        executors.forEach(ExecutorService::shutdown);
    }
}

