package org.edu_sharing.elasticsearch.tracker.activities;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTimebaseTracker;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTimebaseTrackerProperties;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTrackingSupport;
import org.edu_sharing.elasticsearch.tracker.core.generic.TimedData;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.UserNodeActivity;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class UserActivitiesTrackerConfig {

    @Bean
    @ConfigurationProperties(prefix = "tracker.useractivity")
    public GenericTimebaseTrackerProperties userActivitiesTrackerProperties() {
        return new GenericTimebaseTrackerProperties();
    }

    @Bean
    public GenericTrackingSupport<UserNodeActivity> userActivitiesTrackerSupport(EduSharingService eduSharingService,  WorkspaceService workspaceService) {
        return new GenericTrackingSupport<>() {

            @Override
            public List<TimedData<UserNodeActivity>> getData(OffsetDateTime fromTimeStamp, OffsetDateTime toTimeStamp, int batchSize) {
                List<UserNodeActivity> userActivitiesSince = eduSharingService.getUserActivitiesSince(fromTimeStamp, toTimeStamp, batchSize);
                return userActivitiesSince.stream()
                        .map(x -> new TimedData<>(x, x.getTimestamp().toInstant().toEpochMilli()))
                        .toList();
            }

            @Override
            public void onHandleData(List<UserNodeActivity> trackingData) throws IOException {
                workspaceService.addUserActivities(trackingData);
            }
        };
    }

    @Bean
    public GenericTimebaseTracker<GenericTimebaseTrackerProperties, UserNodeActivity> userActivitiesTracker(GenericTimebaseTrackerProperties userActivitiesTrackerProperties, GenericTrackingSupport<UserNodeActivity> userActivitiesTrackerSupport) {
        return new GenericTimebaseTracker<>(userActivitiesTrackerProperties, userActivitiesTrackerSupport);
    }

}
