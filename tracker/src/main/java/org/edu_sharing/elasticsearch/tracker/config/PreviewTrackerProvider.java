package org.edu_sharing.elasticsearch.tracker.config;

import org.edu_sharing.elasticsearch.tracker.PreviewTracker;
import org.springframework.stereotype.Component;

@Component("previewTrackerProvider")
public class PreviewTrackerProvider implements TrackerProvider<PreviewTracker> {
    @Override
    public PreviewTracker create() {
        return new PreviewTracker();
    }
}
