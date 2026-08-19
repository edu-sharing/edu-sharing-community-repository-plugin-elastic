package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.util.ObjectBuilder;
import org.edu_sharing.elasticsearch.alfresco.client.AlfrescoWebscriptClient;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.tools.ScriptExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the write path behind SuggestionTracker/RelationTracker: a node update used to
 * be silently dropped on a version conflict (updateByQuery + Conflicts.Proceed, no retry) or on a
 * not-yet-refreshed document. See WorkspaceService.updateNodeAndPublishedCopies().
 */
@ExtendWith(MockitoExtension.class)
class WorkspaceServiceUpdateTest {

    private static final String INDEX = "workspace_test";

    @Mock
    private ElasticsearchClient client;
    @Mock
    private ScriptExecutor scriptExecutor;
    @Mock
    private EduSharingService eduSharingService;
    @Mock
    private AlfrescoWebscriptClient alfrescoClient;

    private WorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        IndexConfiguration workspace = new IndexConfiguration(b -> b.index(INDEX));
        workspaceService = new WorkspaceService(client, scriptExecutor, eduSharingService, alfrescoClient, workspace,
                mock(NodeFailureService.class));
        ReflectionTestUtils.setField(workspaceService, "updateRetryOnConflict", 5);
    }

    private void stubUpdateByQuery(long updated, long versionConflicts) throws IOException {
        UpdateByQueryResponse response = mock(UpdateByQueryResponse.class);
        when(response.failures()).thenReturn(List.of());
        when(response.updated()).thenReturn(updated);
        when(response.versionConflicts()).thenReturn(versionConflicts);
        when(client.updateByQuery(any(Function.class))).thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private UpdateByQueryRequest capturedUpdateByQueryRequest() throws IOException {
        ArgumentCaptor<Function<UpdateByQueryRequest.Builder, ObjectBuilder<UpdateByQueryRequest>>> captor =
                ArgumentCaptor.forClass(Function.class);
        verify(client).updateByQuery(captor.capture());
        return captor.getValue().apply(new UpdateByQueryRequest.Builder()).build();
    }

    private static ElasticsearchException elasticsearchException(int status, String errorType) {
        ErrorCause cause = ErrorCause.of(c -> c.type(errorType).reason(errorType));
        ErrorResponse response = ErrorResponse.of(r -> r.status(status).error(cause));
        return new ElasticsearchException("update", response);
    }

    @Test
    void writesTheNodeItselfViaRealtimeUpdateWithRetryOnConflict() throws IOException {
        // Arrange
        UpdateResponse<Void> updateResponse = mock(UpdateResponse.class);
        when(updateResponse.result()).thenReturn(Result.Updated);
        when(client.update(any(UpdateRequest.class), eq(Void.class))).thenReturn(updateResponse);
        stubUpdateByQuery(0, 0);

        // Act
        WorkspaceService.FieldUpdateOutcome outcome =
                workspaceService.updateNodesWithSuggestions("node-1", List.of(Map.of("id", "s1")));

        // Assert: written via a realtime _update (not updateByQuery), with retry_on_conflict set -
        // this is what actually fixes the previously silent version-conflict drop.
        ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
        verify(client).update(captor.capture(), eq(Void.class));
        UpdateRequest<?, ?> request = captor.getValue();
        assertThat(request.id()).isEqualTo("node-1");
        assertThat(request.index()).isEqualTo(INDEX);
        assertThat(request.retryOnConflict()).isEqualTo(5);
        assertThat(request.script().source()).contains("ctx._source.suggestions");
        assertThat(outcome.primaryMissing()).isFalse();
    }

    @Test
    void doesNotDoubleWriteTheNodeViaUpdateByQueryAnymore() throws IOException {
        // Arrange
        UpdateResponse<Void> updateResponse = mock(UpdateResponse.class);
        when(updateResponse.result()).thenReturn(Result.Updated);
        when(client.update(any(UpdateRequest.class), eq(Void.class))).thenReturn(updateResponse);
        stubUpdateByQuery(0, 0);

        // Act
        workspaceService.updateNodesWithSuggestions("node-1", List.of(Map.of("id", "s1")));

        // Assert: the updateByQuery call must target only published copies now - no "_id" should
        // clause for the node itself (that used to write the same document a second time).
        Query query = capturedUpdateByQueryRequest().query();
        assertThat(query.isBool()).isTrue();
        assertThat(query.bool().should()).isEmpty();
        assertThat(query.bool().must()).hasSize(2);
    }

    @Test
    void documentMissingOnThePrimaryIsReportedButNotTreatedAsAnError() throws IOException {
        // Arrange: the node has not been indexed yet (or was deleted) - client.update throws.
        when(client.update(any(UpdateRequest.class), eq(Void.class)))
                .thenThrow(elasticsearchException(404, "document_missing_exception"));
        stubUpdateByQuery(0, 0);

        // Act
        WorkspaceService.FieldUpdateOutcome outcome =
                workspaceService.updateNodesWithSuggestions("node-1", List.of(Map.of("id", "s1")));

        // Assert: no exception propagates, but the outcome makes the gap visible, and published
        // copies are still attempted.
        assertThat(outcome.primaryMissing()).isTrue();
        verify(client).updateByQuery(any(Function.class));
    }

    @Test
    void aRealConflictAfterAllRetriesPropagatesSoTheTrackerDoesNotCommit() throws IOException {
        // Arrange: a genuine version conflict (exhausted retry_on_conflict) or mapping error must
        // fail the batch - silently swallowing it is exactly the old bug.
        when(client.update(any(UpdateRequest.class), eq(Void.class)))
                .thenThrow(elasticsearchException(409, "version_conflict_engine_exception"));

        // Act & Assert
        assertThatThrownBy(() -> workspaceService.updateNodesWithSuggestions("node-1", List.of(Map.of("id", "s1"))))
                .isInstanceOf(ElasticsearchException.class);
    }

    @Test
    void versionConflictsOnPublishedCopiesAreReportedInTheOutcome() throws IOException {
        // Arrange
        UpdateResponse<Void> updateResponse = mock(UpdateResponse.class);
        when(updateResponse.result()).thenReturn(Result.Updated);
        when(client.update(any(UpdateRequest.class), eq(Void.class))).thenReturn(updateResponse);
        stubUpdateByQuery(2, 3);

        // Act
        WorkspaceService.FieldUpdateOutcome outcome =
                workspaceService.updateNodesWithSuggestions("node-1", List.of(Map.of("id", "s1")));

        // Assert
        assertThat(outcome.copiesUpdated()).isEqualTo(2);
        assertThat(outcome.copiesConflicts()).isEqualTo(3);
    }

    @Test
    void updateNodesWithRelationsUsesTheSameFieldUpdatePath() throws IOException {
        // Arrange
        UpdateResponse<Void> updateResponse = mock(UpdateResponse.class);
        when(updateResponse.result()).thenReturn(Result.Updated);
        when(client.update(any(UpdateRequest.class), eq(Void.class))).thenReturn(updateResponse);
        stubUpdateByQuery(0, 0);

        // Act
        workspaceService.updateNodesWithRelations("node-1", List.of());

        // Assert
        ArgumentCaptor<UpdateRequest> captor = ArgumentCaptor.forClass(UpdateRequest.class);
        verify(client).update(captor.capture(), eq(Void.class));
        assertThat(captor.getValue().script().source()).contains("ctx._source.relations");
    }
}
