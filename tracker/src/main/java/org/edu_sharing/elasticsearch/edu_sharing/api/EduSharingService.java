package org.edu_sharing.elasticsearch.edu_sharing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.edu_sharing.elasticsearch.alfresco.client.NodePreview;
import org.edu_sharing.elasticsearch.edu_sharing.api.preview.PreviewApi;
import org.edu_sharing.elasticsearch.edu_sharing.api.preview.PreviewData;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.generated.repository.backend.services.rest.client.api.*;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.*;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.*;


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
    private final NodeV1Api nodeV1Api;
    private final SharingV1Api sharingV1Api;
    private final RelationV1Api relationV1Api;
    private final SuggestionsV1Api suggestionsV1Api;
    private final MdsService mdsService;
    private final ObjectMapper objectMapper;
    private final MdsV1Api mdsV1Api;


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
            mds = getDefaultMdsId();
        }
        return mds;
    }

    public String getDefaultMdsId() {
        return mdsService.getMetadataSet(DEFAULT_MDS_ID).getId();
    }

    public void translateProperties(org.edu_sharing.elasticsearch.alfresco.client.NodeData nodeData, String mds, Map.Entry<String, Serializable> prop) {

        String key = CCConstants.getValidLocalName(prop.getKey());
        if (key == null) {
            key = prop.getKey();
        }

        Map<String, List<String>> translations =  translateValuespaceProperty(Tools.getUUID(nodeData.getNodeMetadata().getNodeRef()), mds, key, prop.getValue());
        if(translations != null) {
            Map<String, Map<String, List<String>>> valueSpaces = nodeData.getValueSpaces();
            translations.forEach((language, translatedList) -> {
                if (!translatedList.isEmpty()) {
                    Map<String, List<String>> propMap = valueSpaces.computeIfAbsent(language, (k) -> new HashMap<>());
                    propMap.put(prop.getKey(), translatedList);
                }
            });
        }


        Set<String> jsonDataPropertyIds = mdsService.getJsonDataPropertyIds(mds);
        if (jsonDataPropertyIds.contains(key)) {
            translateJsonDataProperty(nodeData.getExtendedData(), prop, key);
        }

        Set<String> flattenedDataPropertyIds = mdsService.getFlattenedDataPropertyIds(mds);
        if (flattenedDataPropertyIds.contains(key)) {
            translateJsonDataProperty(nodeData.getFlattenedData(), prop, key);
        }
    }

    private void translateJsonDataProperty(Map<String, Map<?, ?>> target, Map.Entry<String, Serializable> prop, String key) {
        if (prop.getValue() instanceof String stringValue) {
            try {
                Map<?, ?> map = objectMapper.readValue(stringValue, Map.class);
                target.put(key, map);
            } catch (JsonProcessingException e) {
                log.error("error reading {}", e.getMessage(), e);
            }
        }
    }


    public Map<String, List<String>> translateValuespaceProperty(String nodeId, String mds, String key, Object value) {
        if (value == null) {
            return null;
        }

        Set<String> valueSpacePropsMds = mdsService.getValueSpaceProbertyIds(mds);
        if (!valueSpacePropsMds.contains(key)) {
            return null;
        }

        Map<String, List<String>> valuespacesForLanguage = new HashMap<>();
        for (String language : valuespaceLanguages) {
            ArrayList<String> translatedList = new ArrayList<>();
            if (value instanceof List<?> listValues) {
                for (Object entry : listValues) {
                    if (entry instanceof String stringValue) {
                        String translatedVal = translate(mds, language, key, stringValue);
                        if (StringUtils.isNotBlank(translatedVal)) {
                            translatedList.add(translatedVal);
                        }
                    } else {
                        log.warn("Can't translate value for field {} of type {} at node {}", key, entry.getClass(), nodeId);
                    }
                }
            } else {
                String translatedVal = translate(mds, language, key, value.toString());
                if (translatedVal != null) {
                    translatedList.add(translatedVal);
                } else {
                    log.warn("Can't translate value for field {} of type {} at node {}", key, value.getClass(), nodeId);
                }
            }

            if (!translatedList.isEmpty()) {
                valuespacesForLanguage.put(language, translatedList);
            }
        }



        return valuespacesForLanguage;
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

        translateSuggestions(data, mds);


        Set<String> valueSpacePropsMds = mdsService.getValueSpaceProbertyIds(mds);
        if (valueSpacePropsMds == null) {
            log.warn("no i18n props found for mds:{}", mds);
            return;
        }

        for (Map.Entry<String, Serializable> prop : properties.entrySet()) {
            translateProperties(data, mds, prop);
        }
    }

    private void translateSuggestions(org.edu_sharing.elasticsearch.alfresco.client.NodeData data, String mds) {
        Set<String> suggestPropertyIds = mdsService.getSuggestPropertyIds(mds);
        if (suggestPropertyIds != null && !suggestPropertyIds.isEmpty()) {
            for(String suggestProp : suggestPropertyIds){
                if(data.getNodeMetadata().getProperties().keySet().contains(CCConstants.getValidGlobalName(suggestProp))){
                    Serializable val = data.getNodeMetadata().getProperties().get(CCConstants.getValidGlobalName(suggestProp));
                    // handle MultiValue/MultiLang
                    val = (Serializable)WorkspaceService.getValue(val,suggestProp);
                    List<String> vals = null;
                    if (val instanceof List<?>) {
                        vals = (List<String>) val;
                    }else if(val instanceof String) {
                        vals = List.of((String)val);
                    }

                    for(String l : valuespaceLanguages){
                        Suggestions suggestions = getValues4Keys(mds, l, suggestProp, vals);
                        Map<String, Map<String, List<String>>> valueSpaces = data.getValueSpaces();
                        suggestions.getValues().forEach((s) -> {
                            Map<String, List<String>> propMap = valueSpaces.computeIfAbsent(l, (k) -> new HashMap<>());
                            if(StringUtils.isNotBlank(s.getTranslation())) {
                                List<String> translations4Prop = propMap.computeIfAbsent(suggestProp, (prop) -> new ArrayList<>());
                                translations4Prop.add(s.getTranslation());
                            }
                        });
                    }
                }
            }
        }
    }

    public List<String> getStatisticsNodeIds(long from, long to) {
        return statisticV1Api.getNodesAlteredInRange1(from, to)
                .block();
    }

    public List<UserNodeActivity> getUserActivitiesSince(OffsetDateTime since, OffsetDateTime until, int maxItems) {
        return trackingV1Api.getAllUserNodeActivities(DEFAULT_REPOSITORY, since, until, maxItems).collectList().block();
    }

    /**
     * @param afterId tiebreaker for oplog entries sharing the exact same "since" timestamp,
     *                forming a combined (timestamp, id) cursor together with "since" - see
     *                SharingApi#getOpLog. Null is treated as "no lower id bound".
     */
    public List<ShareInfoOplog> getShareInfoOplog(OffsetDateTime since, Long afterId, OffsetDateTime until, int maxItems) {
        return sharingV1Api.getOpLog(DEFAULT_REPOSITORY, afterId, since, until, maxItems).collectList().block();
    }

    public List<ShareInfo> getShareInfos(List<Long> shareIds) {
        return sharingV1Api.getShares1(DEFAULT_REPOSITORY, shareIds).collectList().block();
    }

    public List<RelationData> getRelations(String nodeId) {
        return relationV1Api.getRawRelations(DEFAULT_REPOSITORY, nodeId).collectList().block();
    }
    public List<PropertySuggestion> getSuggestions(String nodeId) {
        return suggestionsV1Api.getRawSuggestionsByNodeId(DEFAULT_REPOSITORY, nodeId, null)
                .collectList()
                .block();
    }

    public String getTextContent(String nodeId) {
        NodeText text = nodeV1Api.getTextContent(DEFAULT_REPOSITORY, nodeId, false).block();
        if(text == null) {
            return null;
        }
        return text.getText();
    }

    public Suggestions getValues4Keys(String mds,String locale, String property,List<String> keys){
        return mdsV1Api.getValues4Keys(DEFAULT_REPOSITORY,mds,locale,"ngsearch",property,keys).block();
    }
}
