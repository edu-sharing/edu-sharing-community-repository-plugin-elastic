package org.edu_sharing.elasticsearch.tracker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class UserActivityTracker {

    private final WorkspaceService elasticWorkspaceService;
    private final EduSharingService eduSharingService;
    private final StatusIndexService<UserActivityTx> userActivityStateService;

    private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    int batchSize = 1000;
    public void track() {
        try {
            UserActivityTx userActivityTx = userActivityStateService.getState();
            OffsetDateTime since = userActivityTx == null
                    ? OffsetDateTime.MIN
                    : OffsetDateTime.ofInstant(Instant.ofEpochMilli(userActivityTx.getLastTimestamp()), ZoneOffset.UTC);

            log.info("starting from: {}", dateFormat.format(since));

            long lastTimestamp;
            int offset = 0;
            int totalItems;
            do {
                lastTimestamp = System.currentTimeMillis();
                UserNodeActivityPageResult userActivitiesSince = eduSharingService.getUserActivitiesSince(since, batchSize, offset);
                List<UserNodeActivity> activities = userActivitiesSince.getActivities();

                elasticWorkspaceService.addUserActivities(activities);

                log.info("found {} activities", activities.size());

                Pagination pagination = userActivitiesSince.getPagination();
                totalItems = pagination.getTotal();
                offset += pagination.getCount();

                // TODO skip by max iterations
            } while (totalItems > offset);

            log.info("finished user activities until: {}", dateFormat.format(OffsetDateTime.ofInstant(Instant.ofEpochMilli(lastTimestamp), ZoneOffset.UTC)));
            userActivityStateService.setState(new UserActivityTx(lastTimestamp));
            elasticWorkspaceService.refreshWorkspace();
        } catch (IOException e) {
            log.error("error while fetching user activities", e);
        }
    }
}
