package org.edu_sharing.elasticsearch.tracker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.ShareInfoTx;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShareInfoTracker {

    private final WorkspaceService elasticWorkspaceService;
    private final EduSharingService eduSharingService;
    private final StatusIndexService<ShareInfoTx> shareInfoStateService;

    private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    int batchSize = 1000;
    int maxIterations = 10;

    public void track() {
        try {
            ShareInfoTx shareInfoTx = shareInfoStateService.getState();

            Long lastTimestamp = Optional.ofNullable(shareInfoTx).map(ShareInfoTx::getLastTimestamp).orElse(null);
            OffsetDateTime lastTimestampDate = Objects.isNull(lastTimestamp) ? null : OffsetDateTime.ofInstant(Instant.ofEpochMilli(lastTimestamp), ZoneOffset.UTC);
            log.info("starting from: {}", Optional.ofNullable(lastTimestampDate).map(dateFormat::format).orElse(null));
            int i = 0;
            do {
                List<ShareInfoOplog> shareInfoOplogs = eduSharingService.getShareInfoOplog(lastTimestampDate, batchSize);
                if (shareInfoOplogs.isEmpty()) {
                    break;
                }

                ShareInfoOplog lastOplog = shareInfoOplogs.get(shareInfoOplogs.size() - 1);
                Long lastOplogId = lastOplog.getId();
                lastTimestamp = lastOplog.getTimestamp().toInstant().toEpochMilli();
                lastTimestampDate = lastOplog.getTimestamp();

                Set<Long> deletedShares = shareInfoOplogs.stream()
                        .filter(x -> x.getAction() == ShareInfoOplog.ActionEnum.DELETE)
                        .map(ShareInfoOplog::getShareId)
                        .collect(Collectors.toSet());

                Set<Long> addedShares = shareInfoOplogs.stream()
                        .filter(x -> x.getAction() == ShareInfoOplog.ActionEnum.CREATE || x.getAction() == ShareInfoOplog.ActionEnum.UPDATE)
                        .map(ShareInfoOplog::getShareId)
                        .collect(Collectors.toSet());

                addedShares.removeAll(deletedShares);
                if (!addedShares.isEmpty()) {
                    List<ShareInfo> shareInfos = eduSharingService.getShareInfos(addedShares.stream().toList());
                    elasticWorkspaceService.addShares(shareInfos);
                    log.info("added {} share infos", shareInfos.size());
                }

                if (!deletedShares.isEmpty()) {
                    elasticWorkspaceService.deleteShares(deletedShares);
                    log.info("deleted {} share infos", deletedShares.size());
                }
                shareInfoStateService.setState(new ShareInfoTx(lastOplogId, lastTimestamp));

            } while (i++ < maxIterations);

            log.info("finished user shares until: {}", Optional.ofNullable(lastTimestampDate).map(dateFormat::format).orElse(null));
            elasticWorkspaceService.refreshWorkspace();
        } catch (IOException e) {
            log.error("error while fetching shares", e);
        }
    }
}
