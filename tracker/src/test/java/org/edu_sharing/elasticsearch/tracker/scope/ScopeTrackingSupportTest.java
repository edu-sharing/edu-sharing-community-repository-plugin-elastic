package org.edu_sharing.elasticsearch.tracker.scope;

import org.edu_sharing.elasticsearch.edu_sharing.api.scope.ScopeApi;
import org.edu_sharing.elasticsearch.edu_sharing.api.scope.ScopeChangeData;
import org.edu_sharing.elasticsearch.edu_sharing.api.scope.ScopeIndexFragment;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.tracker.core.generic.TimedData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScopeTrackingSupportTest {

    private static final OffsetDateTime AT = OffsetDateTime.of(2026, 10, 9, 12, 0, 0, 0, ZoneOffset.UTC);

    private ScopeApi scopeApi;
    private WorkspaceService workspaceService;
    private ScopeTrackingSupport underTest;

    @BeforeEach
    void setUp() throws IOException {
        scopeApi = mock(ScopeApi.class);
        workspaceService = mock(WorkspaceService.class);
        when(workspaceService.findChildObjectIds(any())).thenReturn(Set.of());
        when(workspaceService.updateNodesWithScopes(anyString(), any())).thenReturn(new WorkspaceService.FieldUpdateOutcome("n", null, 0, 0));
        when(scopeApi.getIndexData(any())).thenAnswer(i -> ((Collection<String>) i.getArgument(0)).stream().map(this::fragment).toList());
        underTest = new ScopeTrackingSupport(scopeApi, workspaceService);
    }

    private ScopeIndexFragment fragment(String nodeId) {
        return new ScopeIndexFragment(nodeId, List.of("GROUP_ORG_wlo"), List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void cursorIsForwardedAndSeqIsTheSortKey() {
        when(scopeApi.getChanges(AT, 5L, null, 100)).thenReturn(List.of(
                new ScopeChangeData(6, "GROUP_ORG_wlo", List.of("n1"), ScopeChangeData.Type.UPSERT, AT)));

        List<TimedData<ScopeChangeData>> data = underTest.getData(AT, 5L, null, 100);

        assertThat(data).hasSize(1);
        assertThat(data.get(0).timestamp()).isEqualTo(AT.toInstant().toEpochMilli());
        assertThat(data.get(0).sortKey()).isEqualTo(6L);
    }

    @Test
    void noCursorYet() {
        when(scopeApi.getChanges(null, null, null, 100)).thenReturn(List.of());
        assertThat(underTest.getData(OffsetDateTime.ofInstant(java.time.Instant.EPOCH, ZoneOffset.UTC), null, null, 100)).isEmpty();
    }

    @Test
    void changedNodesAndTheirChildObjectsAreRewritten() throws IOException {
        when(workspaceService.findChildObjectIds(any())).thenReturn(Set.of("child1"));

        underTest.onHandleData(List.of(
                new ScopeChangeData(1, "GROUP_ORG_wlo", List.of("n1", "n2"), ScopeChangeData.Type.UPSERT, AT),
                new ScopeChangeData(2, null, List.of("n2"), ScopeChangeData.Type.DELETE, AT)));

        verify(scopeApi).getIndexData(List.of("n1", "n2", "child1"));
        for (String nodeId : List.of("n1", "n2", "child1")) {
            verify(workspaceService).updateNodesWithScopes(eq(nodeId), eq(fragment(nodeId).toFields()));
        }
        verify(workspaceService, never()).removeScope(anyString());
        verify(workspaceService).refreshWorkspace();
    }

    @Test
    void deletedScopeIsRemovedFromAllDocuments() throws IOException {
        underTest.onHandleData(List.of(new ScopeChangeData(3, "GROUP_ORG_gone", List.of(), ScopeChangeData.Type.SCOPE_DELETED, AT)));

        verify(workspaceService).removeScope("GROUP_ORG_gone");
        verifyNoInteractions(scopeApi);
    }

    @Test
    void indexDataIsRequestedInBatches() throws IOException {
        List<String> nodes = IntStream.range(0, ScopeTrackingSupport.INDEX_BATCH_SIZE + 1).mapToObj(i -> "n" + i).toList();

        underTest.onHandleData(List.of(new ScopeChangeData(1, "GROUP_ORG_wlo", nodes, ScopeChangeData.Type.UPSERT, AT)));

        verify(scopeApi, times(2)).getIndexData(any());
        verify(workspaceService, times(nodes.size())).updateNodesWithScopes(anyString(), any());
    }
}
