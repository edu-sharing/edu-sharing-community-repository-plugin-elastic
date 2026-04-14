package org.edu_sharing.elasticsearch.metric;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static org.edu_sharing.elasticsearch.metric.MetricContext.PROGRESS_FACTOR;

@Component
@RequiredArgsConstructor
public class MetricContextFactory {

    private final MeterRegistry meterRegistry;

    public MetricContext createMetric(String name){
        MetricContext metric =  MetricContext.builder()
                .labelProgress(name + "Progress")
                .descriptionProgress(name.toUpperCase() + "progress")
                .labelDelay(name + "Delay")
                .descriptionDelay(name.toUpperCase() + " Delay in seconds")
                .build();

        Gauge.builder(metric.getLabelProgress(), metric.getProgress(), (p) -> p.get() / ((double) PROGRESS_FACTOR))
                .description(metric.getDescriptionProgress())
                .register(meterRegistry);

        Gauge.builder(metric.getLabelDelay(), metric.getTimestamp(), p -> (System.currentTimeMillis() - p.get()) / 1000.)
                .description(metric.getDescriptionDelay())
                .register(meterRegistry);

        return metric;
    }
}
