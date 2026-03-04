package org.edu_sharing.elasticsearch.tracker.suggestions;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTimebaseTracker;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTimebaseTrackerProperties;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTrackingSupport;
import org.edu_sharing.elasticsearch.tracker.core.generic.TimedData;
import org.edu_sharing.elasticsearch.tracker.relation.RelationTrackerConfig;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.PropertySuggestion;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class SuggestionTrackerConfig {

    @Bean
    @ConfigurationProperties(prefix = "tracker.suggestion")
    public GenericTimebaseTrackerProperties suggestionTrackerProperties() {
        return new GenericTimebaseTrackerProperties();
    }

    public record DataStatus(PropertySuggestion data, boolean isDeleted) {
    }

    @Bean
    public GenericTrackingSupport<DataStatus> suggestionTrackerSupport(EduSharingService eduSharingService, WorkspaceService workspaceService) {
        return new GenericTrackingSupport<>() {

            @Override
            public List<TimedData<DataStatus>> getData(OffsetDateTime fromTimeStamp, OffsetDateTime toTimeStamp, int batchSize) {
                List<PropertySuggestion> updatedData = eduSharingService.getSuggestionsSince(fromTimeStamp, toTimeStamp, batchSize, false);
                List<PropertySuggestion> deletedData = eduSharingService.getSuggestionsSince(fromTimeStamp, toTimeStamp, batchSize, true);
                return Stream.concat(
                                updatedData.stream().map(x -> new DataStatus(x, false)),
                                deletedData.stream().map(x -> new DataStatus(x, true)))
                        .map(x -> new TimedData<>(x, x.data().getTimestamp().toInstant().toEpochMilli()))
                        .toList();
            }

            @Override
            public void onHandleData(List<DataStatus> trackingData) {
                trackingData.stream()
                        .filter(x -> x.isDeleted)
                        .map(x -> x.data)
                        .collect(Collectors.groupingBy(PropertySuggestion::getNodeId))
                        .forEach(workspaceService::removeSuggestionsFromNodes);


                trackingData.stream()
                        .filter(x -> !x.isDeleted)
                        .map(x -> x.data)
                        .collect(Collectors.groupingBy(PropertySuggestion::getNodeId))
                        .forEach(workspaceService::updateNodesWithSuggestions);


            }
        };
    }

    @Bean
    public GenericTimebaseTracker<GenericTimebaseTrackerProperties, DataStatus> suggestionTracker(
            GenericTimebaseTrackerProperties suggestionTrackerProperties,
            GenericTrackingSupport<DataStatus> suggestionTrackerSupport) {

        return new GenericTimebaseTracker<>(suggestionTrackerProperties, suggestionTrackerSupport);
    }
}
