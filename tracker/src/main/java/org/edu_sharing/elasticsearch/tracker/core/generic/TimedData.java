package org.edu_sharing.elasticsearch.tracker.core.generic;

/**
 * sortKey is an optional secondary cursor key (e.g. a row id) for data sources where multiple
 * entries can share the exact same timestamp - it lets {@link GenericTimebaseTracker} page through
 * such ties without skipping or re-fetching entries. Data sources without a stable id can omit it.
 */
public record TimedData<DATA>(DATA data, long timestamp, Long sortKey) {

    public TimedData(DATA data, long timestamp) {
        this(data, timestamp, null);
    }
}
