package org.edu_sharing.elasticsearch.metric;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
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
        Gauge.builder("transactionProgress", MetricContextHolder.getTransactionContext().getProgress(),
                (p) -> p.get() /((double) PROGRESS_FACTOR)).description("Transaction progress").register(meterRegistry);
        Gauge.builder("transactionDelay",  MetricContextHolder.getTransactionContext().getTimestamp(),
                p -> (System.currentTimeMillis() - p.get()) / 1000.
        ).description("Transaction Delay in seconds").register(meterRegistry);

        Gauge.builder("aclProgress", MetricContextHolder.getAclContext().getProgress(),
                (p) -> p.get() /((double) PROGRESS_FACTOR)).description("ACL progress").register(meterRegistry);
        Gauge.builder("aclDelay",  MetricContextHolder.getAclContext().getTimestamp(),
                p -> (System.currentTimeMillis() - p.get()) / 1000.
        ).description("ACL Delay in seconds").register(meterRegistry);
    }
}
