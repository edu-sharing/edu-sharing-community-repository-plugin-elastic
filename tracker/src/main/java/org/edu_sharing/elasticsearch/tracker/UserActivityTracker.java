package org.edu_sharing.elasticsearch.tracker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.ShareInfoTx;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.UserActivityTx;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.Pagination;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.UserNodeActivity;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.UserNodeActivityPageResult;
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
public class UserActivityTracker {

    private final WorkspaceService elasticWorkspaceService;
    private final EduSharingService eduSharingService;
    private final StatusIndexService<UserActivityTx> userActivityStateService;

    private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    int batchSize = 1000;
    int maxIterations = 10;
    public void track() {
        try {
            UserActivityTx userActivityTx = userActivityStateService.getState();


            Long lastTimestamp = Optional.ofNullable(userActivityTx).map(UserActivityTx::getLastTimestamp).orElse(null);


            OffsetDateTime lastTimestampDate = Objects.isNull(lastTimestamp) ? null : OffsetDateTime.ofInstant(Instant.ofEpochMilli(lastTimestamp), ZoneOffset.UTC);
            log.info("starting from: {}", Optional.ofNullable(lastTimestampDate).map(dateFormat::format).orElse(null));

            int i = 0;
            do {
                List<UserNodeActivity> activities = eduSharingService.getUserActivitiesSince(lastTimestampDate, batchSize);
                if(activities.isEmpty()) {
                    break;
                }

                UserNodeActivity lastUserActivity = activities.get(activities.size() - 1);
                lastTimestamp = lastUserActivity.getTimestamp().toInstant().toEpochMilli();
                lastTimestampDate = lastUserActivity.getTimestamp();

                elasticWorkspaceService.addUserActivities(activities);

                log.info("found {} activities", activities.size());
                userActivityStateService.setState(new UserActivityTx(lastTimestamp));
            } while (i++ < maxIterations);

            log.info("finished user activities until: {}", Optional.ofNullable(lastTimestampDate).map(dateFormat::format).orElse(null));

            elasticWorkspaceService.refreshWorkspace();
        } catch (IOException e) {
            log.error("error while fetching user activities", e);
        }
    }
}
