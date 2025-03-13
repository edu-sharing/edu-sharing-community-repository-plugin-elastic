package org.edu_sharing.elasticsearch.metric;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * service to access metric data
 * Will be sent to prometheus/actuator endpoints
 */
public class MetricContextHolder {
    @Getter
    private static final MetricContext transactionContext = new MetricContext();
    @Getter
    private static final MetricContext aclContext = new MetricContext();

    @Getter
    public static class MetricContext {
        public static final long PROGRESS_FACTOR = 1000000;
        AtomicLong progress = new AtomicLong();
        AtomicLong timestamp = new AtomicLong(System.currentTimeMillis());
    }
}
