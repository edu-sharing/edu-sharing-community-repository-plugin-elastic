package org.edu_sharing.elasticsearch.tracker.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.edu_sharing.elasticsearch.tracker.core.config.TrackerScheduleProperties;

@RequiredArgsConstructor
public abstract class AbstractTrackerCoroutine<PROPS extends TrackerScheduleProperties> implements Tracker<Void>, TrackerCoroutineConfig {

    protected final PROPS props;

    @Getter
    @Setter
    protected String name;

    @Override
    public TrackerScheduleProperties getConfig() {
        return props;
    }

    public Tracker<Void> getTracker() {
        return this;
    }




}
