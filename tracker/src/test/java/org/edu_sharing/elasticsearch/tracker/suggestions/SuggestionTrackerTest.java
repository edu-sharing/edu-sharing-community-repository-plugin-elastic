package org.edu_sharing.elasticsearch.tracker.suggestions;

import co.elastic.clients.elasticsearch.core.GetResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.model.ElasticNode;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.PropertySuggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the bug where a node with suggestions still ended up with an empty
 * {@code suggestions: []} array in the index (see SuggestionTracker/WorkspaceService).
 */
@ExtendWith(MockitoExtension.class)
class SuggestionTrackerTest {

    private static final String WORKSPACE_NODE_1 = "workspace://SpacesStore/uuid-1";

    @Mock
    private EduSharingService eduSharingService;
    @Mock
    private WorkspaceService workspaceService;

    private SuggestionTracker tracker;

    @BeforeEach
    void setUp() {
        AlfTransactionTrackerProperties props = new AlfTransactionTrackerProperties();
        props.setThreads(1);

        tracker = new SuggestionTracker(props, new ObjectMapper());
        tracker.setEduSharingService(eduSharingService);
        tracker.setWorkspaceService(workspaceService);
    }

    private static Node node(String nodeRef, String status, long id, long txnId) {
        return Node.builder().id(id).nodeRef(nodeRef).status(status).txnId(txnId).build();
    }

    private static PropertySuggestion suggestion(String id, String nodeId) {
        return new PropertySuggestion()
                .id(id)
                .nodeId(nodeId)
                .propertyId("ccm:taxonid")
                .value("value-1")
                .status(PropertySuggestion.StatusEnum.PENDING)
                .type(PropertySuggestion.TypeEnum.AI)
                .description("suggested by AI");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> capturedSuggestions(String nodeId) throws IOException {
        ArgumentCaptor<Collection> captor = ArgumentCaptor.forClass(Collection.class);
        verify(workspaceService).updateNodesWithSuggestions(eq(nodeId), captor.capture());
        return (List<Map<String, Object>>) (List<?>) captor.getValue();
    }

    @Test
    void aSuggestionIsWrittenEvenWhenTheMdsCannotBeResolvedAtAll() throws IOException {
        // Arrange: this is the core regression - the node's mds cannot be determined at all
        // (index lookup fails), which used to make getNodePropertySuggestion() return null and the
        // whole batch collapse into an empty list via .filter(Objects::nonNull).
        Node node = node(WORKSPACE_NODE_1, "u", 1, 100);
        when(eduSharingService.getSuggestions("uuid-1")).thenReturn(List.of(suggestion("s1", "uuid-1")));
        when(workspaceService.get("uuid-1", ElasticNode.class)).thenThrow(new IOException("index unavailable"));
        when(workspaceService.updateNodesWithSuggestions(any(), any()))
                .thenReturn(new WorkspaceService.FieldUpdateOutcome("uuid-1", null, 0, 0));

        // Act
        tracker.trackNodes(List.of(node));

        // Assert: the suggestion is still written - with exactly one entry, not an empty list -
        // just without the (optional) i18n enrichment.
        List<Map<String, Object>> written = capturedSuggestions("uuid-1");
        assertThat(written).hasSize(1);
        assertThat(written.get(0)).containsEntry("id", "s1").doesNotContainKey("i18n");
    }

    @Test
    void aNodeWithoutCmEduMetadatasetFallsBackToTheDefaultMds() throws IOException {
        // Arrange: e.g. a ccm:map node, which never carries cm:edu_metadataset.
        Node node = node(WORKSPACE_NODE_1, "u", 1, 100);
        ElasticNode indexed = new ElasticNode();
        indexed.setProperties(Map.of("cm:name", "some map"));
        GetResponse<ElasticNode> found = mock(GetResponse.class);
        when(found.found()).thenReturn(true);
        when(found.source()).thenReturn(indexed);

        when(eduSharingService.getSuggestions("uuid-1")).thenReturn(List.of(suggestion("s1", "uuid-1")));
        when(workspaceService.get("uuid-1", ElasticNode.class)).thenReturn(found);
        when(eduSharingService.getDefaultMdsId()).thenReturn("-default-");
        when(eduSharingService.translateValuespaceProperty("uuid-1", "-default-", "ccm:taxonid", "value-1"))
                .thenReturn(Map.of("de", List.of("Wert 1")));
        when(workspaceService.updateNodesWithSuggestions(any(), any()))
                .thenReturn(new WorkspaceService.FieldUpdateOutcome("uuid-1", null, 0, 0));

        // Act
        tracker.trackNodes(List.of(node));

        // Assert
        List<Map<String, Object>> written = capturedSuggestions("uuid-1");
        assertThat(written).hasSize(1);
        assertThat(written.get(0)).containsEntry("i18n", Map.of("de", List.of("Wert 1")));
        verify(eduSharingService).translateValuespaceProperty("uuid-1", "-default-", "ccm:taxonid", "value-1");
    }

    @Test
    void deletedNodesAreSkipped() throws IOException {
        Node deletedNode = node(WORKSPACE_NODE_1, "d", 1, 100);

        tracker.trackNodes(List.of(deletedNode));

        verifyNoInteractions(eduSharingService, workspaceService);
    }

    @Test
    void duplicateNodesForTheSameUuidAreWrittenOnlyOnce() throws IOException {
        // Arrange: the same node touched by two different transactions in one batch - same UUID,
        // different txnId. filterIndexableNodes must dedup on the UUID (the actual write key) and
        // keep only the latest one.
        Node first = node(WORKSPACE_NODE_1, "u", 1, 100);
        Node second = node(WORKSPACE_NODE_1, "u", 2, 101);
        when(eduSharingService.getSuggestions("uuid-1")).thenReturn(List.of());
        when(workspaceService.updateNodesWithSuggestions(any(), any()))
                .thenReturn(new WorkspaceService.FieldUpdateOutcome("uuid-1", null, 0, 0));

        tracker.trackNodes(List.of(first, second));

        verify(eduSharingService, times(1)).getSuggestions("uuid-1");
        verify(workspaceService, times(1)).updateNodesWithSuggestions(eq("uuid-1"), any());
    }

    @Test
    void anEmptyRepositoryResponseIsWrittenAsAnEmptyList() throws IOException {
        // Deleting a suggestion is a legitimate case - an empty list must still be written so the
        // index reflects the deletion.
        Node node = node(WORKSPACE_NODE_1, "u", 1, 100);
        when(eduSharingService.getSuggestions("uuid-1")).thenReturn(List.of());
        when(workspaceService.updateNodesWithSuggestions(any(), any()))
                .thenReturn(new WorkspaceService.FieldUpdateOutcome("uuid-1", null, 0, 0));

        tracker.trackNodes(List.of(node));

        List<Map<String, Object>> written = capturedSuggestions("uuid-1");
        assertThat(written).isEmpty();
        // mds resolution is pointless work when there is nothing to translate - must not be attempted.
        verify(workspaceService, never()).get(any(), any());
    }

    @Test
    void aNullRepositoryResponseFailsTheBatchInsteadOfBeingTreatedAsEmpty() {
        // A null response (e.g. a degraded/guest answer) must never be silently treated as "no
        // suggestions" - that would write an incorrect empty array and, since the tracker commits its
        // state right after trackNodes(), the loss would be permanent.
        Node node = node(WORKSPACE_NODE_1, "u", 1, 100);
        when(eduSharingService.getSuggestions("uuid-1")).thenReturn(null);

        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> tracker.trackNodes(List.of(node)));

        verifyNoInteractions(workspaceService);
    }
}
