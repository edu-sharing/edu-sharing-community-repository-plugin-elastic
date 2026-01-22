package org.edu_sharing.elasticsearch.tracker.config;

import org.edu_sharing.elasticsearch.tracker.PreviewTracker;
import org.edu_sharing.elasticsearch.tracker.TransactionTrackerBase;
import org.springframework.stereotype.Component;

@Component("previewTrackerProvider")
public class PreviewTrackerProvider implements TrackerProvider{
    @Override
    public TransactionTrackerBase create() {
        return new PreviewTracker();
    }
}
