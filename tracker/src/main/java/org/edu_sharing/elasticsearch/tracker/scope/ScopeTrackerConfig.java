package org.edu_sharing.elasticsearch.tracker.scope;

import org.edu_sharing.elasticsearch.edu_sharing.api.scope.ScopeApi;
import org.edu_sharing.elasticsearch.edu_sharing.api.scope.ScopeChangeData;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTimebaseTracker;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTimebaseTrackerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Scope tracker, same pattern as the share tracker (time based cursor over a repository oplog).
 * It runs while scopes are disabled as well: the repository serves the change feed independent of the feature flag.
 */
@Configuration
public class ScopeTrackerConfig {

    @Bean
    @ConfigurationProperties(prefix = "tracker.scope")
    public GenericTimebaseTrackerProperties scopeTrackerProperties() {
        return new GenericTimebaseTrackerProperties();
    }

    @Bean
    public ScopeTrackingSupport scopeTrackerSupport(ScopeApi scopeApi, WorkspaceService workspaceService) {
        return new ScopeTrackingSupport(scopeApi, workspaceService);
    }

    @Bean
    public GenericTimebaseTracker<GenericTimebaseTrackerProperties, ScopeChangeData> scopeTracker(GenericTimebaseTrackerProperties scopeTrackerProperties, ScopeTrackingSupport scopeTrackerSupport) {
        return new GenericTimebaseTracker<>(scopeTrackerProperties, scopeTrackerSupport);
    }
}
