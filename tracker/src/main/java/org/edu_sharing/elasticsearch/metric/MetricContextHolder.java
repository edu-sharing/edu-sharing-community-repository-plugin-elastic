package org.edu_sharing.elasticsearch.metric;

import lombok.Builder;
import lombok.Getter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * service to access metric data
 * Will be sent to prometheus/actuator endpoints
 */
public class MetricContextHolder {
    @Builder
    @Getter
    public static class MetricContext {
        public static final long PROGRESS_FACTOR = 1000000;

        @Builder.Default
        AtomicLong progress = new AtomicLong();
        @Builder.Default
        AtomicLong timestamp = new AtomicLong(System.currentTimeMillis());
        String labelProgress;
        String descriptionProgress;
        String labelDelay;
        String descriptionDelay;
    }
}
