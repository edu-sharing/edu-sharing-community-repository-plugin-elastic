package org.edu_sharing.elasticsearch.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.TrackerAvailabilityTickService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationCompletedAware;
import org.edu_sharing.elasticsearch.tracker.Tracker;
import org.springframework.scheduling.annotation.Scheduled;

@Slf4j
@RequiredArgsConstructor
public class UserActivityTrackerJob implements MigrationCompletedAware {

    private final Tracker tracker;
    private final TrackerAvailabilityTickService tickService;

    private boolean migrated = false;


    /**
     * no race condition possibe with track() cause all scheduled tasks are executed by single thread
     * <a href="https://stackoverflow.com/questions/24033208/how-to-prevent-overlapping-schedules-in-spring">...</a>
     */
    @Scheduled(fixedDelayString = "${userActivities.delay}")
    public void track() {
        tickService.tick();
        if (!migrated) {
            return;
        }

        tracker.track();
    }

    @Override
    public void MigrationCompleted() {
        migrated = true;
    }
}
