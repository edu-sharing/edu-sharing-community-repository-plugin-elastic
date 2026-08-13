package org.edu_sharing.elasticsearch.tracker.share;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTimebaseTracker;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTimebaseTrackerProperties;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTrackingSupport;
import org.edu_sharing.elasticsearch.tracker.core.generic.TimedData;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.ShareInfo;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.ShareInfoOplog;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class ShareTrackerConfig {

    @Bean
    @ConfigurationProperties(prefix = "tracker.share")
    public GenericTimebaseTrackerProperties shareTrackerProperties() {
        return new GenericTimebaseTrackerProperties();
    }

    @Bean
    public GenericTrackingSupport<ShareInfoOplog> shareTrackerSupport(EduSharingService eduSharingService, WorkspaceService workspaceService) {
        return new GenericTrackingSupport<>() {

            @Override
            public List<TimedData<ShareInfoOplog>> getData(OffsetDateTime fromTimeStamp, Long afterId, OffsetDateTime toTimeStamp, int batchSize) {
                List<ShareInfoOplog> shareInfoOplogs = eduSharingService.getShareInfoOplog(fromTimeStamp, afterId, toTimeStamp, batchSize);
                return shareInfoOplogs.stream()
                        .map(x -> new TimedData<>(x, x.getTimestamp().toInstant().toEpochMilli(), x.getId()))
                        .toList();
            }

            @Override
            public void onHandleData(List<ShareInfoOplog> trackingData) {
                try {
                    Set<Long> deletedShares = trackingData.stream()
                            .filter(x -> x.getAction() == ShareInfoOplog.ActionEnum.DELETE)
                            .map(ShareInfoOplog::getShareId)
                            .collect(Collectors.toSet());

                    Set<Long> addedShares = trackingData.stream()
                            .filter(x -> x.getAction() == ShareInfoOplog.ActionEnum.CREATE || x.getAction() == ShareInfoOplog.ActionEnum.UPDATE)
                            .map(ShareInfoOplog::getShareId)
                            .collect(Collectors.toSet());

                    addedShares.removeAll(deletedShares);
                    if (!addedShares.isEmpty()) {
                        List<ShareInfo> shareInfos = eduSharingService.getShareInfos(addedShares.stream().toList());
                        workspaceService.addShares(shareInfos);
                        log.info("added {} share infos", shareInfos.size());
                    }

                    if (!deletedShares.isEmpty()) {
                        workspaceService.deleteShares(deletedShares);
                        log.info("deleted {} share infos", deletedShares.size());
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    @Bean
    public GenericTimebaseTracker<GenericTimebaseTrackerProperties, ShareInfoOplog> shareTracker(GenericTimebaseTrackerProperties shareTrackerProperties, GenericTrackingSupport<ShareInfoOplog> shareTrackerSupport) {
        return new GenericTimebaseTracker<>(shareTrackerProperties, shareTrackerSupport);
    }
}
