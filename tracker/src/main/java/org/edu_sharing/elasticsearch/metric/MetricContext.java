package org.edu_sharing.elasticsearch.metric;

import lombok.Builder;
import lombok.Getter;

import java.util.concurrent.atomic.AtomicLong;

@Builder
@Getter
public class MetricContext {
    public static final long PROGRESS_FACTOR = 1000000;

    @Builder.Default
    AtomicLong progress = new AtomicLong();
    @Builder.Default
    AtomicLong timestamp = new AtomicLong(System.currentTimeMillis());
    /**
     * name of the tracker this context belongs to. every tracker feeds the same two gauges and is told apart
     * by this value in the {@code tracker} tag - see {@link MetricContextFactory}.
     */
    String name;
}
