package org.edu_sharing.elasticsearch.tracker.suggestions;

import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.elasticsearch.core.model.ElasticNode;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.core.AbstractAlfTransactionTracker;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.PropertySuggestion;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SuggestionTracker extends AbstractAlfTransactionTracker<AlfTransactionTrackerProperties> {

    private final ObjectMapper mapper;

    public SuggestionTracker(AlfTransactionTrackerProperties suggestionTrackerProperties, ObjectMapper mapper) {
        super(suggestionTrackerProperties);
        this.mapper = mapper;
    }

    @Override
    public void trackNodes(List<Node> nodes) throws IOException {
        Map<Node, List<Map<String, Object>>> nodeSuggestions = new ConcurrentHashMap<>();

        this.threadUtil.runThreaded(
                nodes,
                node -> {
                    String nodeId = Tools.getUUID(node.getNodeRef());
                    List<PropertySuggestion> suggestions = eduSharingService.getSuggestions(nodeId);
                    if (suggestions.isEmpty()) {
                        nodeSuggestions.put(node, List.of());
                        return;
                    }
                    String mds = getMetadataSet(nodeId);
                    List<Map<String, Object>> nodePropertySuggestions = suggestions
                            .stream()
                            .map(suggestion -> getNodePropertySuggestion(suggestion, mds))
                            .filter(Objects::nonNull)
                            .toList();
                    nodeSuggestions.put(node, nodePropertySuggestions);
                },
                true,
                true);


        threadUtil.runThreaded(nodeSuggestions.entrySet(), entry -> updateNodesWithSuggestions(entry.getKey(), entry.getValue()), true, true);
        workspaceService.refreshWorkspace();
    }

    @Nullable
    private String getMetadataSet(String nodeId) {
        try {
            HitsMetadata<ElasticNode> search = workspaceService.search(QueryBuilders.ids(i -> i.values(nodeId)), 0, 1, null, ElasticNode.class);
            if (search == null || search.hits().isEmpty()) {
                log.warn("Node not found in index for: {}", nodeId);
                return null;
            }

            return Optional.of(search)
                    .map(HitsMetadata::hits)
                    .flatMap(s -> s.stream().findAny())
                    .map(Hit::source)
                    .map(ElasticNode::getProperties)
                    .map(props -> (String) props.get("cm:edu_metadataset"))
                    .orElseGet(() -> {
                        log.warn("cm:edu_metadataset not found in index for: {}", nodeId);
                        return null;
                    });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    private Map<String, Object> getNodePropertySuggestion(PropertySuggestion suggestion, @Nullable String mds) {
        if (mds == null) {
            return null;
        }
        Map<String, List<String>> i18n = eduSharingService.translateValuespaceProperty(suggestion.getNodeId(), mds, suggestion.getPropertyId(), suggestion.getValue());
        Map<String, Object> suggestionMap = mapper.convertValue(suggestion, Map.class);
        suggestionMap.put("i18n", i18n);

        return suggestionMap;
    }

    private void updateNodesWithSuggestions(Node node, List<Map<String, Object>> nodePropertySuggestions) {
        workspaceService.updateNodesWithSuggestions(Tools.getUUID(node.getNodeRef()), nodePropertySuggestions);
    }
}
