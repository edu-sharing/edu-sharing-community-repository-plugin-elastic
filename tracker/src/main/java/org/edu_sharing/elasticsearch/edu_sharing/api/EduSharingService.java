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
import org.edu_sharing.generated.repository.backend.services.rest.client.model.*;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.*;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@Service
@RequiredArgsConstructor
public class EduSharingService {
    private final NetworkV1Api networkV1Api;
    private final StatisticV1Api statisticV1Api;
    private final MdsV1Api mdsV1Api;
    private final AboutApi aboutApi;
    private final PreviewApi previewApi;
    private final NodeV1Api nodeV1Api;

    @Value("${valuespace.languages}")
    private String[] valuespaceLanguages;

    @Value("${valuespace.cache.check.after.ms : 120000}")
    private long valuespaceCacheCheckAfterMs = 120000;

    @Value("${tracker.fetchThumbnails}")
    boolean fetchThumbnails;

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
        return mdsV1Api.getMetadataSets("-home-").block();
    }

    public List<String> getValuespaceProperties(String mdsId) {
        return mdsV1Api.getMetadataSet("-home-", mdsId)
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
                    for (Object value : (List<?>)prop.getValue()) {
                        if (value instanceof String) {
                            String translatedVal = translate(mds, language, key, (String) value);
                            if (translatedVal != null && !StringUtils.isBlank(translatedVal)) {
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

    public Suggestions getValuespace(String mds, String language, String property) {

        Suggestions entries = getValuespaceFromCache(mds, language, property);

        if (entries != null) {
            log.debug("got valuespace entries from cache");
            return entries;
        }

        SuggestionParam suggestionParam = SuggestionParam.builder()
                .valueParameters(ValueParameters.builder().query("ngsearch").build())
                .build();

        entries = mdsV1Api.getValues("-home-", mds, suggestionParam).block();
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

    public void addPreview(org.edu_sharing.elasticsearch.alfresco.client.NodeData node) {
        if (!fetchThumbnails) {
            return;
        }

        String nodeId = Tools.getUUID(node.getNodeMetadata().getNodeRef());
        String storeProtocol = Tools.getProtocol(node.getNodeMetadata().getNodeRef());
        String storeId = Tools.getIdentifier(node.getNodeMetadata().getNodeRef());

        NodePreview preview = new NodePreview();
        preview.setIsIcon(false);
        PreviewData previewSmall = previewApi.getPreviewData(storeProtocol, storeId, nodeId, 400, 400, 60);

        NodeEntry nodeEntry = nodeV1Api.getMetadata("-home-", nodeId, null).block();
        if (nodeEntry != null) {
            Node nodeData = nodeEntry.getNode();
            if (nodeData.getPreview() != null) {
                preview.setIsIcon(nodeData.getPreview().getIsIcon());
                preview.setType(nodeData.getPreview().getType());
            }
        }

        if (previewSmall != null && !preview.isIcon()) {
            if (previewSmall.getData() != null && (previewSmall.getData().length / 1024) > previewMaxKiloBytes) {
                return;
            }
            preview.setMimetype(previewSmall.getMimetype());
            preview.setSmall(previewSmall.getData());
        }

        node.setNodePreview(preview);
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

    public List<String> getStatisticsNodeIds(long timestamp) {
        return statisticV1Api.getNodesAlteredInRange1(timestamp)
                .block();
    }
}
