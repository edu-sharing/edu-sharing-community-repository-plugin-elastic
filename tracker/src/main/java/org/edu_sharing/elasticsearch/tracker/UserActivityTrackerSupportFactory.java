package org.edu_sharing.elasticsearch.tracker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationCompletedAware;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.UserActivityTx;
import org.edu_sharing.elasticsearch.tracker.generic.GenericTrackingSupport;
import org.edu_sharing.elasticsearch.tracker.generic.TrackingSupportFactory;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.UserNodeActivity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserActivityTrackerSupportFactory implements TrackingSupportFactory<UserNodeActivity, UserActivityTx> {

    private final WorkspaceService elasticWorkspaceService;
    private final EduSharingService eduSharingService;


    public List<GenericTrackingSupport<UserNodeActivity, UserActivityTx>> getTrackingSupport() {
        return List.of(new UserActivityTrackerSupport());
    }


    private class UserActivityTrackerSupport implements GenericTrackingSupport<UserNodeActivity, UserActivityTx> {

        @Override
        public String getName() {
            return "Update user activities";
        }

        @Override
        public List<UserNodeActivity> getData(OffsetDateTime lastTimestamp, int batchSize) {
            return eduSharingService.getUserActivitiesSince(lastTimestamp, batchSize);
        }

        @Override
        public Long getTimestamp(UserNodeActivity userNodeActivity) {
            return userNodeActivity.getTimestamp().toInstant().toEpochMilli();
        }

        @Override
        public UserActivityTx stateApplier(UserActivityTx userActivityTx, long lastTimestamp) {
            return new UserActivityTx(lastTimestamp);
        }

        @Override
        public Long lastTimestampSupplier(UserActivityTx userActivityTx) {
            return userActivityTx.getLastTimestamp();
        }

        @Override
        public void onHandleData(List<UserNodeActivity> trackingData) throws IOException {
            elasticWorkspaceService.addUserActivities(trackingData);
        }
    }
}
