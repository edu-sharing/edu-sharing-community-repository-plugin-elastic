package org.edu_sharing.elasticsearch.edu_sharing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.edu_sharing.elasticsearch.alfresco.client.NodePreview;
import org.edu_sharing.elasticsearch.edu_sharing.api.preview.PreviewApi;
import org.edu_sharing.elasticsearch.edu_sharing.api.preview.PreviewData;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.generated.repository.backend.services.rest.client.api.*;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.*;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@Service
@RequiredArgsConstructor
public class EduSharingService {
    public static final String DEFAULT_REPOSITORY = "-home-";
    public static final String DEFAULT_MDS_ID = "-default-";

    private final NetworkV1Api networkV1Api;
    private final StatisticV1Api statisticV1Api;
    private final PreviewApi previewApi;
    private final TrackingV1Api trackingV1Api;
    private final SharingV1Api sharingV1Api;
    private final RelationV1Api relationV1Api;
    private final SuggestionsV1Api suggestionsV1Api;
    private final MdsService mdsService;
    private final ObjectMapper objectMapper;


    @Value("${valuespace.languages}")
    private String[] valuespaceLanguages;


    @Value("${preview.maxKiloBytes : 100}")
    long previewMaxKiloBytes;

    public Repo getHomeRepository() {
        return networkV1Api.getRepositories()
                .mapNotNull(x -> x.getRepositories()
                        .stream()
                        .filter(y -> Boolean.TRUE.equals(y.getIsHomeRepo()))
                        .findFirst()
                        .orElseThrow(() -> new RuntimeException("Home repository not found")))
                .block();
    }

    public List<NodeData> getStatisticsForNode(String nodeId, long timestamp) {
        return statisticV1Api.getNodeData(nodeId, timestamp)
                .collectList()
                .block();
    }

    public String getMdsId(org.edu_sharing.elasticsearch.alfresco.client.NodeData nodeData) {
        String mds = (String) nodeData.getNodeMetadata().getProperties().get(CCConstants.CM_PROP_METADATASET_EDU_METADATASET);
        if (mds == null) {
            mds = mdsService.getMetadataSet(DEFAULT_MDS_ID).getId();
        }
        return mds;
    }

    public void translateProperty(org.edu_sharing.elasticsearch.alfresco.client.NodeData nodeData, String mds, Map.Entry<String, Serializable> prop) {

        String key = CCConstants.getValidLocalName(prop.getKey());
        if (key == null) {
            key = prop.getKey();
        }

        Set<String> valueSpacePropsMds = mdsService.getValueSpaceProbertyIds(mds);
        if (valueSpacePropsMds.contains(key)) {
            translateValuespaceProperty(nodeData, mds, prop, key);
        }

        Set<String> jsonDataPropertyIds = mdsService.getJsonDataPropertyIds(mds);
        if (jsonDataPropertyIds.contains(key)) {
            translateJsonDataProperty(nodeData, prop, key);
        }
    }

    private void translateJsonDataProperty(org.edu_sharing.elasticsearch.alfresco.client.NodeData nodeData, Map.Entry<String, Serializable> prop, String key) {
        if(prop.getValue() instanceof String stringValue) {
            try {
                Map<?,?> map = objectMapper.readValue(stringValue, Map.class);
                Map<String, Map<?, ?>> extendedData = nodeData.getExtendedData();
                extendedData.put(key, map);
            } catch (JsonProcessingException e) {
                log.error("error reading {}", e.getMessage(), e);
            }
        }

    }


    private void translateValuespaceProperty(org.edu_sharing.elasticsearch.alfresco.client.NodeData nodeData, String mds, Map.Entry<String, Serializable> prop, String key) {
        if (prop.getValue() == null) {
            return;
        }

        for (String language : valuespaceLanguages) {
            Map<String, List<String>> valuespacesForLanguage = nodeData
                    .getValueSpaces()
                    .computeIfAbsent(language, k -> new ConcurrentHashMap<>());

            if (prop.getValue() instanceof List<?> listValues) {
                ArrayList<String> translatedList = new ArrayList<>();
                for (Object value : listValues) {
                    if (value instanceof String stringValue) {
                        String translatedVal = translate(mds, language, key, stringValue);
                        if (StringUtils.isNotBlank(translatedVal)) {
                            translatedList.add(translatedVal);
                        }
                    } else {
                        log.warn("Can't translate value for field {} of type {} at node {}", key, value.getClass(), nodeData.getNodeMetadata().getNodeRef());
                    }
                }
                if (!translatedList.isEmpty()) {
                    valuespacesForLanguage.put(prop.getKey(), translatedList);
                }
            } else {
                String translatedVal = translate(mds, language, key, prop.getValue().toString());
                if (translatedVal != null) {
                    valuespacesForLanguage.put(prop.getKey(), Collections.singletonList(translatedVal));
                }
            }
        }
    }

    public String translate(String mds, String language, String property, String key) {
        return mdsService.getValuespace(mds, language, property)
                .getValues()
                .stream()
                .filter(entry -> key.equals(entry.getKey()))
                .map(Suggestion::getDisplayString)
                .findFirst()
                .orElse(null);
    }

    public NodePreview getPreviewDataByNodeRef(String nodeRef) {
        String nodeId = Tools.getUUID(nodeRef);
        String storeProtocol = Tools.getProtocol(nodeRef);
        String storeId = Tools.getIdentifier(nodeRef);

        NodePreview preview = new NodePreview();
        PreviewData previewSmall = previewApi.getPreviewData(storeProtocol, storeId, nodeId, 400, 400, 60);

        if (previewSmall != null && !preview.isIcon()) {
            if (previewSmall.getData() != null && (previewSmall.getData().length / 1024) > previewMaxKiloBytes) {
                log.info("Skipping preview for {} cause size {}kb exceeds limit {}kb", nodeRef, previewSmall.getData().length / 1024, previewMaxKiloBytes);
                return null;
            }
            preview.setMimetype(previewSmall.getMimetype());
            preview.setSmall(previewSmall.getData());
            preview.setIcon(previewSmall.isIcon());
            preview.setType(previewSmall.getType());
        }

        return preview;
    }

    public void translateValuespaceProps(org.edu_sharing.elasticsearch.alfresco.client.NodeData data) {

        Map<String, Serializable> properties = data.getNodeMetadata().getProperties();

        String mds = getMdsId(data);

        Set<String> valueSpacePropsMds = mdsService.getValueSpaceProbertyIds(mds);
        if (valueSpacePropsMds == null) {
            log.warn("no i18n props found for mds:{}", mds);
            return;
        }

        for (Map.Entry<String, Serializable> prop : properties.entrySet()) {
            translateProperty(data, mds, prop);
        }
    }

    public List<String> getStatisticsNodeIds(long from, long to) {
        return statisticV1Api.getNodesAlteredInRange1(from, to)
                .block();
    }

    public List<UserNodeActivity> getUserActivitiesSince(OffsetDateTime since, OffsetDateTime until, int maxItems) {
        return trackingV1Api.getAllUserNodeActivities(DEFAULT_REPOSITORY, since, until, maxItems).collectList().block();
    }

    public List<ShareInfoOplog> getShareInfoOplog(OffsetDateTime since, OffsetDateTime until, int maxItems) {
        return sharingV1Api.getOpLog(DEFAULT_REPOSITORY, null, since, until, maxItems).collectList().block();
    }

    public List<ShareInfo> getShareInfos(List<Long> shareIds) {
        return sharingV1Api.getShares1(DEFAULT_REPOSITORY, shareIds).collectList().block();
    }

    public List<NodeRelationData> getRelations(String nodeId) {
        return relationV1Api.getRelations(DEFAULT_REPOSITORY, nodeId).collectList().block();
    }

    public List<RelationData> getRelationsSince(OffsetDateTime since, OffsetDateTime until, int batchSize, boolean deleted) {
        return relationV1Api.getTrackedRelation(DEFAULT_REPOSITORY, since, until, batchSize, deleted)
                .flatMap(x -> Flux.just(x, x.toBuilder()
                        .fromNode(x.getToNode())
                        .toNode(x.getFromNode())
                        .type(RelationData.TypeEnum.fromValue(x.getReverseType().getValue()))
                        .reverseType(RelationData.ReverseTypeEnum.fromValue(x.getType().getValue()))
                        .build()))
                .collectList()
                .block();
    }

    public List<PropertySuggestion> getSuggestionsSince(OffsetDateTime since, OffsetDateTime until, int batchSize, boolean deleted) {
        return suggestionsV1Api.getTrackedRelation1(DEFAULT_REPOSITORY, since, until, batchSize, deleted)
                .collectList()
                .block();
    }
}
