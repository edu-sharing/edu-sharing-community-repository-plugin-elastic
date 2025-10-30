package org.edu_sharing.elasticsearch.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.TrackerAvailabilityTickService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationCompletedAware;
import org.edu_sharing.elasticsearch.tracker.AclTracker;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@RequiredArgsConstructor
public class AclTrackerJob implements MigrationCompletedAware {

    private final AclTracker aclTracker;
    private final TrackerAvailabilityTickService tickService;

    private boolean migrated = false;

    AtomicInteger counter = new AtomicInteger(0);


    @Scheduled(fixedDelayString = "${tracker.delay}")
    public void track() {
        tickService.tick();
        if (!migrated) {
            return;
        }
        int i = counter.incrementAndGet();
        log.info("Starting Job {}",i);
        boolean aclChanges;
        do {
            aclChanges = aclTracker.track();
            log.info("recursive aclChanges: {}", aclChanges);
        } while (aclChanges);
        log.info("Finished Job {}",i);
        counter.decrementAndGet();
    }

    @Override
    public void MigrationCompleted() {
        migrated = true;
    }
}
