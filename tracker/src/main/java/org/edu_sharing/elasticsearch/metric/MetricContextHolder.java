package org.edu_sharing.elasticsearch.metric;

import lombok.Builder;
import lombok.Getter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * service to access metric data
 * Will be sent to prometheus/actuator endpoints
 */
public class MetricContextHolder {
    @Getter
    private static final MetricContext transactionContext = MetricContext.builder()
            .labelProgress("transactionProgress")
            .descriptionProgress("Transaction progress")
            .labelDelay("transactionDelay")
            .descriptionDelay("Transaction Delay in seconds").build();
    @Getter
    private static final MetricContext aclContext = MetricContext.builder()
            .labelProgress("aclProgress")
            .descriptionProgress("ACL progress")
            .labelDelay("aclDelay")
            .descriptionDelay("ACL Delay in seconds").build();
    @Getter
    private static final MetricContext cascadeContext = MetricContext.builder()
            .labelProgress("cascadeProgress")
            .descriptionProgress("Cascade progress")
            .labelDelay("cascadeDelay")
            .descriptionDelay("Cascade Delay in seconds").build();

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
