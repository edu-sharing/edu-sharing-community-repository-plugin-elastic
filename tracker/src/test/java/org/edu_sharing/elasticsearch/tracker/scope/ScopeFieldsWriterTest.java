package org.edu_sharing.elasticsearch.tracker.scope;

import org.edu_sharing.elasticsearch.edu_sharing.api.scope.ScopeApi;
import org.edu_sharing.elasticsearch.edu_sharing.api.scope.ScopeIndexFragment;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScopeFieldsWriterTest {

    private final ScopeApi scopeApi = mock(ScopeApi.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final ScopeFieldsWriter underTest = new ScopeFieldsWriter(scopeApi, workspaceService);

    @Test
    void onlyDocumentsWithEntriesAreWritten() throws IOException {
        ScopeIndexFragment withEntry = new ScopeIndexFragment("n1", List.of("GROUP_ORG_wlo"), List.of(), List.of(), List.of(), List.of());
        ScopeIndexFragment withoutEntry = new ScopeIndexFragment("n2", List.of(), List.of(), List.of(), List.of(), List.of());
        when(scopeApi.getIndexData(List.of("n1", "n2"))).thenReturn(List.of(withEntry, withoutEntry));

        underTest.writeForNewDocuments(List.of("n1", "n2"));

        verify(workspaceService).updateNodesWithScopes("n1", withEntry.toFields());
        verify(workspaceService, never()).updateNodesWithScopes(eq("n2"), any());
    }

    @Test
    void failuresDoNotBreakTheIndexing() {
        when(scopeApi.getIndexData(any())).thenThrow(new IllegalStateException("repository not reachable"));

        assertThatCode(() -> underTest.writeForNewDocuments(List.of("n1"))).doesNotThrowAnyException();
        verifyNoInteractions(workspaceService);
    }

    @Test
    void nothingToDo() {
        underTest.writeForNewDocuments(List.of());
        verifyNoInteractions(scopeApi);
    }
}
