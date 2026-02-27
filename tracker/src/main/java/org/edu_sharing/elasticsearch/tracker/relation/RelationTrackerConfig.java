package org.edu_sharing.elasticsearch.tracker.relation;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTimebaseTracker;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTimebaseTrackerProperties;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTrackingSupport;
import org.edu_sharing.elasticsearch.tracker.core.generic.TimedData;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.RelationData;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Configuration
@RequiredArgsConstructor
public class RelationTrackerConfig {

    @Bean
    @ConfigurationProperties(prefix = "tracker.relation")
    public GenericTimebaseTrackerProperties relationTrackerProperties() {
        return new GenericTimebaseTrackerProperties();
    }

    public record RelationDataStatus(RelationData data, boolean isDeleted) {
    }

    @Bean
    public GenericTrackingSupport<RelationDataStatus> relationTrackerSupport(EduSharingService eduSharingService, WorkspaceService workspaceService) {
        return new GenericTrackingSupport<>() {

            @Override
            public List<TimedData<RelationDataStatus>> getData(OffsetDateTime fromTimeStamp, OffsetDateTime toTimeStamp, int batchSize) {
                List<RelationData> updatedData = eduSharingService.getRelationsSince(fromTimeStamp, toTimeStamp, batchSize, false);
                List<RelationData> deletedData = eduSharingService.getRelationsSince(fromTimeStamp, toTimeStamp, batchSize, true);
                return Stream.concat(
                                updatedData.stream().map(x -> new RelationDataStatus(x, false)),
                                deletedData.stream().map(x -> new RelationDataStatus(x, true)))
                        .map(x -> new TimedData<>(x, x.data.getTimestamp().toInstant().toEpochMilli()))
                        .toList();
            }

            @Override
            public void onHandleData(List<RelationDataStatus> trackingData) {
                trackingData.stream()
                        .filter(x -> x.isDeleted)
                        .map(x -> x.data)
                        .collect(Collectors.groupingBy(RelationData::getFromNode))
                        .forEach(workspaceService::removeRelationsFromNodes);


                trackingData.stream()
                        .filter(x -> !x.isDeleted)
                        .map(x -> x.data)
                        .collect(Collectors.groupingBy(RelationData::getFromNode))
                        .forEach(workspaceService::updateNodesWithRelations);


            }
        };
    }

    @Bean
    public GenericTimebaseTracker<GenericTimebaseTrackerProperties, RelationDataStatus> relationTracker(GenericTimebaseTrackerProperties relationTrackerProperties, GenericTrackingSupport<RelationDataStatus> relationTrackerSupport) {
        return new GenericTimebaseTracker<>(relationTrackerProperties, relationTrackerSupport);
    }
}
