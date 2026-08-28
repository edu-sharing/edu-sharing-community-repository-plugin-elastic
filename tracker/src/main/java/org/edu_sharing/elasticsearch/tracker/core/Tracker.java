package org.edu_sharing.elasticsearch.tracker.core;

public interface Tracker<STATUS> {
    State track(TrackingContext<STATUS> context);

    /**
     * Whether this tracker can express its position as a percentage of the work its data source
     * holds. That needs a countable total the source reports along with the data (Alfresco returns
     * {@code maxTxnId} / {@code maxAclChangeSetId} with every page), which not every source has: a
     * purely time windowed API can only say "here is the next batch", never "and this many are
     * left". Such a tracker returns false and only feeds {@code trackerDelay}, whose value - the
     * age of the last processed entry - is exact for it, instead of filling the shared
     * {@code trackerProgress} gauge with a number that would mean something different than it does
     * for every other tracker.
     *
     * @see org.edu_sharing.elasticsearch.metric.MetricContextFactory
     */
    default boolean reportsProgress() {
        return true;
    }

    enum State {
        IN_PROGRESS,
        FINISHED,
        EXCEPTION
    }
}
