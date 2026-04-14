package org.edu_sharing.elasticsearch.tracker.core;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.edu_sharing.elasticsearch.tracker.core.config.BaseTrackerProperties;
import org.edu_sharing.elasticsearch.tracker.strategy.CommiteTimeStatus;

@RequiredArgsConstructor
public abstract class AbstractTracker<PROPS extends BaseTrackerProperties, STATUS extends CommiteTimeStatus> implements Tracker<STATUS>, TrackerConfig<PROPS, STATUS> {

    protected final PROPS props;

    @Getter
    @Setter
    protected String name;

    public Tracker<STATUS> getTracker() {
        return this;
    }

    @Override
    public PROPS getConfig() {
        return props;
    }
}
