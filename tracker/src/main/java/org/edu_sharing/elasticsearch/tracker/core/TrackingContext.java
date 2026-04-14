package org.edu_sharing.elasticsearch.tracker.core;

import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceInterface;
import org.edu_sharing.elasticsearch.metric.MetricContext;
import org.edu_sharing.elasticsearch.tracker.strategy.TrackerStrategy;

public record TrackingContext<STATE>(
        TrackerStrategy strategy,
        StatusIndexServiceInterface<STATE> statusIndexService,
        MetricContext metricContext
) {
}
