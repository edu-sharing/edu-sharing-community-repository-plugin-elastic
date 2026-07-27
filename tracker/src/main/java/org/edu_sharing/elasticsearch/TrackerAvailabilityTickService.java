package org.edu_sharing.elasticsearch;

public interface TrackerAvailabilityTickService {

    /**
     * tell the application that the given tracker is still alive and has just triggered a new run.
     * Used for liveness probes - tracked per tracker name so a single stuck tracker is detected
     * even while every other tracker keeps making progress.
     */
    void tick(String trackerName);

    /**
     * tell the application that the given tracker/migration step has legitimately concluded and
     * is no longer expected to make progress, so it must stop counting towards the liveness check.
     * Without this, a tracker/step that finished cleanly would keep its last tick timestamp
     * forever and inevitably age past the liveness threshold on its own, even though nothing is
     * actually stuck.
     */
    void clear(String trackerName);
}
