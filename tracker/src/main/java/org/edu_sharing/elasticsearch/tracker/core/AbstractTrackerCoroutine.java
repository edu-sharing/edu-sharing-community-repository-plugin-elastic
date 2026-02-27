package org.edu_sharing.elasticsearch.tracker.core;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.tracker.core.config.BaseTrackerProperties;

@RequiredArgsConstructor
public abstract class AbstractTrackerCoroutine<PROPS extends BaseTrackerProperties> implements Tracker<Void>, TrackerCoroutineConfig {

    protected final PROPS props;

    @Override
    public BaseTrackerProperties getConfig() {
        return props;
    }

    public Tracker<Void> getTracker() {
        return this;
    }




}
