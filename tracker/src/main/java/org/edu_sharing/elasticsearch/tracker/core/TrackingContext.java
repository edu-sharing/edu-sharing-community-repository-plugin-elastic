package org.edu_sharing.elasticsearch.tracker.core;

import lombok.NonNull;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceInterface;
import org.edu_sharing.elasticsearch.metric.MetricContextHolder;
import org.edu_sharing.elasticsearch.tracker.strategy.TrackerStrategy;
import org.jetbrains.annotations.NotNull;

public record TrackingContext<STATE>(
        @NotNull @NonNull TrackerStrategy strategy,
        @NotNull @NonNull StatusIndexServiceInterface<STATE> statusIndexService,
        @NotNull @NonNull MetricContextHolder.MetricContext metricContext
) {
}
