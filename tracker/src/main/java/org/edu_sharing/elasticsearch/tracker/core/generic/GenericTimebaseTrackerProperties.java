package org.edu_sharing.elasticsearch.tracker.core.generic;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.edu_sharing.elasticsearch.tracker.core.config.BaseTrackerProperties;

@Data
@EqualsAndHashCode(callSuper = true)
public class GenericTimebaseTrackerProperties extends BaseTrackerProperties {
    private int batchSize = 1000;
    private int maxIterations = 10;
}
