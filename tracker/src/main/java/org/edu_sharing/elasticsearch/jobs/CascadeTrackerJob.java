package org.edu_sharing.elasticsearch.jobs;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.tracker.CascadeTracker;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RequiredArgsConstructor
public class CascadeTrackerJob {

    private final CascadeTracker cascadeTracker;

    AtomicInteger counter = new AtomicInteger(0);
    @Scheduled(fixedDelay = 5000, scheduler = "cascadeScheduler")
    public void runJob() {
        int i = counter.incrementAndGet();
        log.info("Starting Job {}",i);
        cascadeTracker.track();
        log.info("Finished Job {}",i);
        counter.decrementAndGet();

    }

}
