package org.edu_sharing.elasticsearch.edu_sharing.api;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.edu_sharing.elasticsearch.alfresco.client.NodePreview;
import org.edu_sharing.elasticsearch.edu_sharing.api.preview.PreviewApi;
import org.edu_sharing.elasticsearch.edu_sharing.api.preview.PreviewData;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.generated.repository.backend.services.rest.client.api.*;
import org.edu_sharing.generated.repository.backend.services.rest.client.handler.ApiClient;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.*;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.jetbrains.annotations.Nullable;
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

    private final NetworkV1Api networkV1Api;
    private final StatisticV1Api statisticV1Api;
    private final MdsV1Api mdsV1Api;
    private final AboutApi aboutApi;
    private final PreviewApi previewApi;
    private final NodeV1Api nodeV1Api;
    private final TrackingV1Api trackingV1Api;
    private final SharingV1Api sharingV1Api;
    private final RelationV1Api relationV1Api;
    private final SuggestionsV1Api suggestionsV1Api;

    @Value("${valuespace.languages}")
    private String[] valuespaceLanguages;

    @Value("${valuespace.cache.check.after.ms : 120000}")
    private long valuespaceCacheCheckAfterMs = 120000;

    @Value("${preview.maxKiloBytes : 100}")
    long previewMaxKiloBytes;


    private final Map<String, Set<String>> valuespaceProps = new HashMap<>();
    Map<String, Map<String, Map<String, Suggestions>>> cache = new HashMap<>();
    long valuespaceCacheLastChecked = -1;
    long valuespaceCacheLastModified = -1;

    @PostConstruct
    public void init() {
        MdsEntries metadataSets = getMetadataSets();
        if (metadataSets != null) {
            for (MetadataSetInfo metadataSet : metadataSets.getMetadatasets()) {
                Set<String> valueSpacePropsTmp = new HashSet<>(getValuespaceProperties(metadataSet.getId()));
                valuespaceProps.put(metadataSet.getId(), valueSpacePropsTmp);
                log.info("added {} i18n props for mds: {}", valueSpacePropsTmp.size(), metadataSet.getId());
            }
        }
    }

    @Nullable
    public MdsEntries getMetadataSets() {
        return mdsV1Api.getMetadataSets(DEFAULT_REPOSITORY).block();
    }

    public List<String> getValuespaceProperties(String mdsId) {
        return mdsV1Api.getMetadataSet(DEFAULT_REPOSITORY, mdsId)
                .map(x -> x.getWidgets()
                        .stream()
                        .filter(Objects::nonNull)
                        .filter(y -> Boolean.TRUE.equals(y.getHasValues()))
                        .map(MdsWidget::getId)
                        .toList())
                .block();
    }

    public Repo getHomeRepository() {
        return networkV1Api.getRepositories()
                .mapNotNull(x -> x.getRepositories()
                        .stream()
                        .filter(y -> Boolean.TRUE.equals(y.getIsHomeRepo()))
                        .findFirst()
                        .orElse(null))
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
            mds = "default";
        }

        if (mds.equals("default")) {
            mds = valuespaceProps.keySet().stream().findFirst().orElse(null);
        }

        return mds;
    }

    public void translateProperty(org.edu_sharing.elasticsearch.alfresco.client.NodeData nodeData, String mds, Set<String> valueSpacePropsMds, Map.Entry<String, Serializable> prop) {
        if (valueSpacePropsMds == null) {
            valueSpacePropsMds = getPropsMdsList(mds);
        }

        String key = CCConstants.getValidLocalName(prop.getKey());
        if (key == null) {
            key = prop.getKey();
        }

        if (valueSpacePropsMds.contains(key)) {
            for (String language : valuespaceLanguages) {
                if (prop.getValue() == null) {
                    continue;
                }

                Map<String, List<String>> valuespacesForLanguage = nodeData.getValueSpaces().computeIfAbsent(language, k -> new ConcurrentHashMap<>());
                if (prop.getValue() instanceof List) {
                    ArrayList<String> translatedList = new ArrayList<>();
                    for (Object value : (List<?>) prop.getValue()) {
                        if (value instanceof String) {
                            String translatedVal = translate(mds, language, key, (String) value);
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
    }

    private Set<String> getPropsMdsList(String mds) {
        return valuespaceProps.get(mds);
    }

    public String translate(String mds, String language, String property, String key) {
        Suggestions entries = getValuespace(mds, language, property);
        String result = null;
        for (Suggestion entry : entries.getValues()) {
            if (key.equals(entry.getKey())) {
                result = entry.getDisplayString();
            }
        }
        return result;
    }

        /**
     * Retrieves the valuespace entries for the specified metadata set, language, and property.
     * If the entries are available in the cache, they are returned directly.
     * Otherwise, the method requests the data from a remote service, updates the cache, and returns the result.
     *
     * @param mds      The metadata set identifier for which the valuespace entries are requested.
     * @param language The language for which the valuespace entries are requested.
     * @param property The specific property within the metadata set for which the valuespace entries are requested.
     * @return The {@link ValuespaceEntries} object representing the retrieved valuespace data.
     */
    public Suggestions getValuespace(String mds, String language, String property) {

        Suggestions entries = getValuespaceFromCache(mds, language, property);

        if (entries != null) {
            log.debug("got valuespace entries from cache");
            return entries;
        }

        SuggestionParam suggestionParam = SuggestionParam.builder()
                .valueParameters(ValueParameters.builder().query("ngsearch").property(property).build())
                .build();
        // @TODO can we provide the header directly to the request?
        synchronized (mdsV1Api) {
            mdsV1Api.getApiClient().addDefaultHeader("locale", language);
            entries = mdsV1Api.getValues(DEFAULT_REPOSITORY, mds, suggestionParam).block();
            mdsV1Api.getApiClient().addDefaultHeader("locale", null);
        }
            addValuespaceToCache(mds, language, property, entries);

        return entries;
    }

    private Suggestions getValuespaceFromCache(String mds, String language, String property) {

        Map<String, Map<String, Suggestions>> mdsMap = cache.get(mds);
        if (mdsMap == null) {
            return null;
        }

        Map<String, Suggestions> propMap = mdsMap.get(language);
        if (propMap == null) {
            return null;
        }
        return propMap.get(property);
    }

    private void addValuespaceToCache(String mds, String language, String property, Suggestions entries) {

        Map<String, Map<String, Suggestions>> mdsMap = cache.computeIfAbsent(mds, k -> new HashMap<>());
        Map<String, Suggestions> propMap = mdsMap.computeIfAbsent(language, k -> new HashMap<>());
        propMap.put(property, entries);
    }

    public void refreshValuespaceCache() {
        if (valuespaceCacheLastChecked == -1
                || valuespaceCacheLastChecked < (System.currentTimeMillis() - valuespaceCacheCheckAfterMs)) {
            log.info("will check if cache in edu-sharing changed");
            About about = aboutApi.about().block();
            if (about != null && about.getLastCacheUpdate() != null && about.getLastCacheUpdate() > valuespaceCacheLastModified) {
                log.info("repos last cache updated{}: force valuespace cache refresh", new Date(about.getLastCacheUpdate()));
                cache.clear();
                valuespaceCacheLastModified = about.getLastCacheUpdate();
            }
            valuespaceCacheLastChecked = System.currentTimeMillis();
        }
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

        Set<String> valueSpacePropsMds = getPropsMdsList(mds);
        if (valueSpacePropsMds == null) {
            log.warn("no i18n props found for mds:{}", mds);
            return;
        }

        for (Map.Entry<String, Serializable> prop : properties.entrySet()) {
            translateProperty(data, mds, valueSpacePropsMds, prop);
        }
    }

    public List<String> getStatisticsNodeIds(long from, long to) {
        return statisticV1Api.getNodesAlteredInRange1(from, to)
                .block();
    }

    public List<UserNodeActivity> getUserActivitiesSince(OffsetDateTime since,OffsetDateTime until, int maxItems) {
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
