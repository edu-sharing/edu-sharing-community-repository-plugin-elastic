package org.edu_sharing.elasticsearch.jobs;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.tracker.CascadeTracker;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RequiredArgsConstructor
public class CascadeTrackerJob {

    private final CascadeTracker cascadeTracker;

    @Scheduled(fixedDelay = 5000)
    public void runJob() {
        log.info("Starting Cascade Tracker Job");
        cascadeTracker.track();
        log.info("Finished Cascade Tracker Job");

    }

}
