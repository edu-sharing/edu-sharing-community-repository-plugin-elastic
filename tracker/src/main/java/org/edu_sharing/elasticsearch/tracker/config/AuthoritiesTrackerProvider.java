package org.edu_sharing.elasticsearch.tracker.config;

import org.edu_sharing.elasticsearch.tracker.AuthoritiesTracker;
import org.springframework.stereotype.Component;

@Component("authoritiesTrackerProvider")
public class AuthoritiesTrackerProvider implements TrackerProvider<AuthoritiesTracker> {
    @Override
    public AuthoritiesTracker create() {
        return new AuthoritiesTracker();
    }
}
