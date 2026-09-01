package org.edu_sharing.elasticsearch.metric;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static org.edu_sharing.elasticsearch.metric.MetricContext.PROGRESS_FACTOR;

/**
 * Registers the tracking gauges. All trackers share the same two metric names and are distinguished by the
 * {@link #TAG_TRACKER} tag, so a single dashboard panel or alert rule covers every tracker - including ones
 * added later - instead of needing one per tracker name.
 */
@Component
@RequiredArgsConstructor
public class MetricContextFactory {

    public static final String GAUGE_PROGRESS = "trackerProgress";
    public static final String GAUGE_DELAY = "trackerDelay";
    public static final String TAG_TRACKER = "tracker";

    private final MeterRegistry meterRegistry;

    /**
     * @param withProgress see {@link org.edu_sharing.elasticsearch.tracker.core.Tracker#reportsProgress()}.
     *                     A tracker that cannot compute a progress must not get the gauge registered at
     *                     all: an unfed gauge would sit at 0 and keep the shared "low tracking progress"
     *                     alert firing forever, which is worse than having no series for it.
     */
    public MetricContext createMetric(String name, boolean withProgress) {
        MetricContext metric = MetricContext.builder()
                .name(name)
                .build();

        if (withProgress) {
            Gauge.builder(GAUGE_PROGRESS, metric.getProgress(), (p) -> p.get() / ((double) PROGRESS_FACTOR))
                    .tag(TAG_TRACKER, name)
                    .description("tracking progress in percent")
                    .register(meterRegistry);
        }

        Gauge.builder(GAUGE_DELAY, metric.getTimestamp(), p -> (System.currentTimeMillis() - p.get()) / 1000.)
                .tag(TAG_TRACKER, name)
                .description("tracking delay in seconds")
                .register(meterRegistry);

        return metric;
    }
}
