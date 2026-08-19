package org.edu_sharing.elasticsearch.metric;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics for nodes that had to be skipped because indexing them failed for a reason that will not
 * go away by retrying (see
 * {@link org.edu_sharing.elasticsearch.elasticsearch.utils.ElasticErrorClassifier}).
 * <p>
 * Skipping advances the transaction marker, so the affected nodes are not picked up again by the
 * regular tracking, and nothing reprocesses them automatically. These metrics plus the dead letter
 * index are the only signal that they exist.
 */
@Component
public class NodeFailureMetrics {

    /** every skip ever, survives nothing but a restart - use it for trending and alerting on rate */
    public static final String COUNTER_NODE_FAILURES = "tracker.node.failures";

    /**
     * Nodes currently sitting in the dead letter index. Since nothing redrives them, this is a
     * backlog that only shrinks through manual repair or a reindex - alert on it growing, not on it
     * being non zero.
     */
    public static final String GAUGE_NODE_FAILURES_PENDING = "tracker.node.failures.pending";

    private final MeterRegistry meterRegistry;
    private final AtomicLong pending = new AtomicLong();

    public NodeFailureMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;

        Gauge.builder(GAUGE_NODE_FAILURES_PENDING, pending, AtomicLong::get)
                .description("known broken nodes in the dead letter index, awaiting manual repair or a reindex")
                .register(meterRegistry);
    }

    /**
     * @param source    tracker name or the method that skipped the node, both bounded
     * @param errorType elasticsearch error type
     */
    public void countSkippedNode(String source, String errorType) {
        meterRegistry.counter(COUNTER_NODE_FAILURES, "source", source, "errorType", errorType).increment();
    }

    public void setPending(long value) {
        pending.set(value);
    }
}
