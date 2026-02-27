package org.edu_sharing.elasticsearch.tracker.core;

public interface Tracker<STATUS> {
    State track(TrackingContext<STATUS> context);

    enum State {
        IN_PROGRESS,
        FINISHED,
        EXCEPTION
    }
}
