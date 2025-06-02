package org.edu_sharing.elasticsearch.metric;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;

import static org.edu_sharing.elasticsearch.metric.MetricContextHolder.MetricContext.PROGRESS_FACTOR;

@Configuration
@AllArgsConstructor
public class MetricConfig {
    private final MeterRegistry meterRegistry;

    @Getter
    private final AtomicLong transactionProgress = new AtomicLong();
    @Getter
    private final AtomicLong transactionTimestamp = new AtomicLong();
    @PostConstruct public void init() {
        MetricContextHolder.MetricContext txContext = MetricContextHolder.getTransactionContext();
        Gauge.builder(txContext.labelProgress, txContext.getProgress(),
                (p) -> p.get() /((double) PROGRESS_FACTOR)).description(txContext.descriptionProgress).register(meterRegistry);
        Gauge.builder(txContext.getLabelProgress(),  txContext.getTimestamp(),
                p -> (System.currentTimeMillis() - p.get()) / 1000.
        ).description(txContext.descriptionDelay).register(meterRegistry);

        MetricContextHolder.MetricContext aclContext = MetricContextHolder.getAclContext();
        Gauge.builder(aclContext.labelProgress, aclContext.getProgress(),
                (p) -> p.get() /((double) PROGRESS_FACTOR)).description(aclContext.descriptionProgress).register(meterRegistry);
        Gauge.builder(aclContext.labelDelay,  aclContext.getTimestamp(),
                p -> (System.currentTimeMillis() - p.get()) / 1000.
        ).description(aclContext.descriptionDelay).register(meterRegistry);

        MetricContextHolder.MetricContext cascadeContext = MetricContextHolder.getCascadeContext();
        Gauge.builder(cascadeContext.labelProgress, cascadeContext.getProgress(),
                (p) -> p.get() /((double) PROGRESS_FACTOR)).description(cascadeContext.descriptionProgress).register(meterRegistry);
        Gauge.builder(cascadeContext.labelDelay,  cascadeContext.getTimestamp(),
                p -> (System.currentTimeMillis() - p.get()) / 1000.
        ).description(cascadeContext.descriptionDelay).register(meterRegistry);
    }
}
