package org.edu_sharing.elasticsearch.tracker.core.generic;

public record TimedData<DATA>(DATA data, long timestamp) {

}
