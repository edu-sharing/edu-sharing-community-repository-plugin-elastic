package org.edu_sharing.elasticsearch.tracker.config;

import org.edu_sharing.elasticsearch.tracker.AuthoritiesTracker;
import org.edu_sharing.elasticsearch.tracker.TransactionTrackerBase;
import org.springframework.stereotype.Component;

@Component("authoritiesTrackerProvider")
public class AuthoritiesTrackerProvider implements TrackerProvider {
    @Override
    public TransactionTrackerBase create() {
        return new AuthoritiesTracker();
    }
}
