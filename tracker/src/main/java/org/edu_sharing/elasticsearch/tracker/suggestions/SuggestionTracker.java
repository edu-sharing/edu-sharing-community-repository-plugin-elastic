package org.edu_sharing.elasticsearch.tracker.suggestions;

import co.elastic.clients.elasticsearch.core.GetResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
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
import java.util.concurrent.atomic.AtomicInteger;

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
        List<Node> relevant = filterIndexableNodes(nodes);
        if (relevant.isEmpty()) {
            return;
        }

        // keyed by UUID, not Node: filterIndexableNodes already deduplicated on that basis, and the
        // UUID is what actually identifies the Elasticsearch document we write to.
        Map<String, List<Map<String, Object>>> nodeSuggestions = new ConcurrentHashMap<>();

        this.threadUtil.runThreaded(
                relevant,
                node -> {
                    String nodeId = Tools.getUUID(node.getNodeRef());
                    List<PropertySuggestion> suggestions = eduSharingService.getSuggestions(nodeId);
                    if (suggestions == null) {
                        // the repository answered without a usable body (e.g. a guest/degraded
                        // response) - never treat that the same as "no suggestions"
                        throw new IOException("no suggestion response from repository for node " + nodeId);
                    }
                    if (suggestions.isEmpty()) {
                        log.debug("Node {} has no suggestions", nodeId);
                        nodeSuggestions.put(nodeId, List.of());
                        return;
                    }
                    // i18n is an enrichment, not a precondition - resolveMds always returns a usable
                    // mds (falling back to the default one) so no suggestion is ever dropped because
                    // of it
                    String mds = resolveMds(nodeId);
                    List<Map<String, Object>> nodePropertySuggestions = suggestions
                            .stream()
                            .map(suggestion -> toSuggestionDocument(suggestion, mds))
                            .toList();
                    log.debug("Node {} has {} suggestions", nodeId, nodePropertySuggestions.size());
                    nodeSuggestions.put(nodeId, nodePropertySuggestions);
                },
                true,
                true);

        AtomicInteger documentMissing = new AtomicInteger();
        AtomicInteger copyConflicts = new AtomicInteger();
        threadUtil.runThreaded(nodeSuggestions.entrySet(), entry -> {
            WorkspaceService.FieldUpdateOutcome outcome =
                    workspaceService.updateNodesWithSuggestions(entry.getKey(), entry.getValue());
            if (outcome.primaryMissing()) {
                documentMissing.incrementAndGet();
            }
            if (outcome.copiesConflicts() > 0) {
                copyConflicts.incrementAndGet();
            }
        }, true, true);

        long withSuggestions = nodeSuggestions.values().stream().filter(v -> !v.isEmpty()).count();
        long emptied = nodeSuggestions.size() - withSuggestions;
        log.info("suggestions written: nodes={} tracked={} withSuggestions={} emptied={} documentMissing={} copyConflicts={}",
                nodes.size(), relevant.size(), withSuggestions, emptied, documentMissing.get(), copyConflicts.get());

        workspaceService.refreshWorkspace();
    }

    /**
     * Resolves the metadataset id used to translate valuespace suggestion values, always returning a
     * usable id where possible.
     * <p>
     * Uses a realtime GET (not {@link WorkspaceService#search}) so it also sees a node the mainTracker
     * just wrote but that is not yet refresh-visible. Falls back to the default mds if the node has no
     * {@code cm:edu_metadataset} (e.g. {@code ccm:map} nodes never have one) - this mirrors
     * {@code EduSharingService.getMdsId}.
     */
    @Nullable
    private String resolveMds(String nodeId) {
        try {
            String mds = Optional.ofNullable(workspaceService.get(nodeId, ElasticNode.class))
                    .filter(GetResponse::found)
                    .map(GetResponse::source)
                    .map(ElasticNode::getProperties)
                    .map(props -> (String) props.get("cm:edu_metadataset"))
                    .orElse(null);
            if (mds == null) {
                mds = eduSharingService.getDefaultMdsId();
                log.debug("no cm:edu_metadataset found for {}, using default mds {}", nodeId, mds);
            }
            return mds;
        } catch (Exception e) {
            log.warn("could not resolve mds for {}, suggestions will be indexed without i18n: {}", nodeId, e.getMessage());
            return null;
        }
    }

    /**
     * Builds the document written into the {@code suggestions} array. Never returns null and never
     * drops the suggestion: {@code i18n} is enrichment on top of the suggestion's own fields, not a
     * precondition for indexing it.
     */
    private Map<String, Object> toSuggestionDocument(PropertySuggestion suggestion, @Nullable String mds) {
        Map<String, Object> suggestionMap = mapper.convertValue(suggestion, new TypeReference<Map<String, Object>>() {});
        if (mds != null) {
            Map<String, List<String>> i18n = eduSharingService.translateValuespaceProperty(
                    suggestion.getNodeId(), mds, suggestion.getPropertyId(), suggestion.getValue());
            if (i18n != null && !i18n.isEmpty()) {
                suggestionMap.put("i18n", i18n);
            }
        }
        return suggestionMap;
    }
}
