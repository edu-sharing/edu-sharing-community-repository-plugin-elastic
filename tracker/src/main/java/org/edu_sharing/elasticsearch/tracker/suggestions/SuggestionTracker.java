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
import org.edu_sharing.elasticsearch.tracker.utils.Partition;
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

        Collection<List<Node>> partitions = Partition.getPartitions(nodes, props.getFetchSizeAlfresco());
        this.threadUtil.runThreaded(
                partitions,
                partition -> partition.forEach(node -> {
                    List<PropertySuggestion> suggestions = eduSharingService.getSuggestions(Tools.getUUID(node.getNodeRef()));
                    List<Map<String, Object>> nodePropertySuggestions = suggestions
                            .stream()
                            .map(this::getNodePropertySuggestion)
                            .filter(Objects::nonNull)
                            .toList();
                    nodeSuggestions.put(node, nodePropertySuggestions);
                }),
                true,
                true);


        nodeSuggestions.forEach(this::updateNodesWithSuggestions);
    }

    @Nullable
    private Map<String, Object> getNodePropertySuggestion(PropertySuggestion suggestion) {
        try {
            HitsMetadata<ElasticNode> search = workspaceService.search(QueryBuilders.ids(i -> i.values(suggestion.getNodeId())), 0, 1, null, ElasticNode.class);
            if (search != null && search.hits().isEmpty()) {
                log.warn("Node not found in index for: {}", suggestion.getNodeId());
                return null;
            }

            String mds = Optional.ofNullable(search)
                    .map(HitsMetadata::hits)
                    .flatMap(s -> s.stream().findAny())
                    .map(Hit::source)
                    .map(ElasticNode::getProperties)
                    .map(props -> (String) props.get("cm:edu_metadataset"))
                    .orElseThrow(() -> {
                        log.warn("Node or cm:edu_metadataset not found in index for: {}", suggestion.getNodeId());
                        return new RuntimeException("Node not found in index for: " + suggestion.getNodeId());
                    });

            Map<String, List<String>> i18n = eduSharingService.translateValuespaceProperty(suggestion.getNodeId(), mds, suggestion.getPropertyId(), suggestion.getValue());
            Map<String, Object> suggestionMap = mapper.convertValue(suggestion, Map.class);
            suggestionMap.put("i18n", i18n);

            return suggestionMap;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void updateNodesWithSuggestions(Node node, List<Map<String, Object>> nodePropertySuggestions) {
        workspaceService.updateNodesWithSuggestions(Tools.getUUID(node.getNodeRef()), nodePropertySuggestions);
    }
}
