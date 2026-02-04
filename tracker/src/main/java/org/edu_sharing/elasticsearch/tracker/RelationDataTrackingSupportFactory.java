package org.edu_sharing.elasticsearch.tracker;

import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.RelationTx;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.UserActivityTx;
import org.edu_sharing.elasticsearch.tracker.generic.GenericTimebaseTracker;
import org.edu_sharing.elasticsearch.tracker.generic.GenericTrackingSupport;
import org.edu_sharing.elasticsearch.tracker.generic.TrackingSupportFactory;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.RelationData;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.UserNodeActivity;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RelationDataTrackingSupportFactory  implements TrackingSupportFactory<RelationData, RelationTx> {

    private final WorkspaceService elasticWorkspaceService;
    private final EduSharingService eduSharingService;


    public List<GenericTrackingSupport<RelationData, RelationTx>> getTrackingSupport() {
        return List.of(new RelationDataTrackingSupport("Update relations", true), new RelationDataTrackingSupport("Delete relations",false));
    }

    @Value
    @RequiredArgsConstructor
    private class RelationDataTrackingSupport implements GenericTrackingSupport<RelationData, RelationTx> {
        String name;
        boolean deletedData;

        @Override
        public String getName() {
            return name;
        }

        @Override
        public List<RelationData> getData(OffsetDateTime lastTimestamp, int batchSize) {
            return eduSharingService.getRelationsSince(lastTimestamp, batchSize, deletedData);
        }

        @Override
        public Long getTimestamp(RelationData relationData) {
            return relationData.getTimestamp().toInstant().toEpochMilli();
        }

        @Override
        public RelationTx stateApplier(RelationTx relationTx, long lastTimestamp) {
            return deletedData ? new RelationTx(relationTx.getUpdatedTimeStamp(), lastTimestamp) : new RelationTx(lastTimestamp, relationTx.getDeletedTimeStamp());
        }

        @Override
        public Long lastTimestampSupplier(RelationTx relationTx) {
            return deletedData ? relationTx.getDeletedTimeStamp() : relationTx.getUpdatedTimeStamp();
        }

        @Override
        public void onHandleData(List<RelationData> trackingData) throws IOException {

            Map<String, List<RelationData>> mergedNodesGroup = trackingData.stream()
                    .collect(Collectors.groupingBy(RelationData::getFromNode));

            for (Map.Entry<String, List<RelationData>> entry : mergedNodesGroup.entrySet()) {
                String nodeId = entry.getKey();
                List<RelationData> relations = entry.getValue();
                if (deletedData) {
                    elasticWorkspaceService.removeRelationsFromNodes(nodeId, relations);
                } else {
                    elasticWorkspaceService.updateNodesWithRelations(nodeId, relations);
                }
            }
        }
    }
}
