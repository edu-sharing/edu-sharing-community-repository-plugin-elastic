package org.edu_sharing.elasticsearch.jobs;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationCompletedAware;
import org.edu_sharing.elasticsearch.tracker.CascadeTracker;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RequiredArgsConstructor
public class CascadeTrackerJob implements MigrationCompletedAware {

    private final CascadeTracker cascadeTracker;

    AtomicInteger counter = new AtomicInteger(0);

    boolean migrationCompleted = false;

    @Scheduled(fixedDelayString="5000",scheduler = "cascadeScheduler")
    public void runJob() {
        if(!migrationCompleted) return;

        int i = counter.incrementAndGet();
        log.info("Starting Job {}",i);
        cascadeTracker.track();
        log.info("Finished Job {}",i);
        counter.decrementAndGet();

    }

    @Override
    public void MigrationCompleted() {
        migrationCompleted = true;
    }
}
