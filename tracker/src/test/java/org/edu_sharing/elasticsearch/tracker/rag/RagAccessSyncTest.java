package org.edu_sharing.elasticsearch.tracker.rag;

import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkAccess;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The access sync exists because none of the events that change who may see a node is a transaction
 * on that node, so the RAG tracker never sees them.
 * <p>
 * What these tests pin down is the decision behind it: the union of authorities is always recomputed
 * and written whole, never patched. Patching would need to know which authority came from which
 * source, and a stale entry there - a deleted copy, a tightened collection ACL - would keep granting
 * access. Replacing cannot drift; the worst it can do is cost a write.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagAccessSyncTest {

    @Mock
    private RagChunkService ragChunkService;

    @Mock
    private RagAccessResolver accessResolver;

    @Captor
    private ArgumentCaptor<List<RagChunkService.AccessUpdate>> updatesCaptor;

    private RagAccessSync sync;

    @BeforeEach
    void setUp() throws IOException {
        sync = new RagAccessSync(50000, ragChunkService, accessResolver);
        when(ragChunkService.updateAccess(any())).thenReturn(Set.of());
        when(ragChunkService.findNodeIdsByAclIds(any(), anyInt())).thenReturn(Set.of());
        when(accessResolver.findNodesWithCollectionAcl(any(), anyInt())).thenReturn(Set.of());
    }

    private static RagChunkService.ChunkState state(String nodeId, int chunkCount) {
        return new RagChunkService.ChunkState(nodeId, "hash", "meta", chunkCount);
    }

    @Test
    void refreshesTheNodesThatCarryTheChangedAclThemselves() throws IOException {
        when(ragChunkService.findNodeIdsByAclIds(any(), anyInt())).thenReturn(Set.of("uuid-1"));
        when(ragChunkService.findIndexState(any()))
                .thenReturn(Map.of("uuid-1", state("uuid-1", 3)));
        when(accessResolver.resolve(any())).thenReturn(Map.of("uuid-1",
                new RagChunkAccess(List.of("GROUP_NEU"), List.of(), List.of(), List.of(), List.of(), false, false)));

        sync.onAclsChanged(List.of(42L));

        verify(ragChunkService).updateAccess(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).singleElement().satisfies(update -> {
            assertThat(update.nodeId()).isEqualTo("uuid-1");
            assertThat(update.chunkCount()).isEqualTo(3);
            assertThat(update.access().readers()).containsExactly("GROUP_NEU");
        });
    }

    @Test
    void alsoRefreshesOriginalsWhoseCollectionsAclChanged() throws IOException {
        // the original is not touched at all when a collection's permissions change, and its own
        // aclId does not match - only the workspace index knows about the copy
        when(accessResolver.findNodesWithCollectionAcl(any(), anyInt())).thenReturn(Set.of("uuid-2"));
        when(ragChunkService.findIndexState(any()))
                .thenReturn(Map.of("uuid-2", state("uuid-2", 2)));
        when(accessResolver.resolve(any())).thenReturn(Map.of("uuid-2",
                new RagChunkAccess(List.of("GROUP_SAMMLUNG"), List.of(), List.of(), List.of(), List.of("coll-1"), false, false)));

        sync.onAclsChanged(List.of(99L));

        verify(ragChunkService).updateAccess(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).singleElement().satisfies(update ->
                assertThat(update.nodeId()).isEqualTo("uuid-2"));
    }

    @Test
    void writesTheResolvedUnionWholeRatherThanPatchingIt() throws IOException {
        // an authority that lost access is simply absent from the new list - no subtraction, and
        // therefore no need to know where any authority came from
        when(ragChunkService.findNodeIdsByAclIds(any(), anyInt())).thenReturn(Set.of("uuid-1"));
        when(ragChunkService.findIndexState(any()))
                .thenReturn(Map.of("uuid-1", state("uuid-1", 1)));
        when(accessResolver.resolve(any())).thenReturn(Map.of("uuid-1",
                new RagChunkAccess(List.of("GROUP_A"), List.of(), List.of(), List.of(), List.of(), false, false)));

        sync.onAclsChanged(List.of(42L));

        verify(ragChunkService).updateAccess(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue().get(0).access().readers()).containsExactly("GROUP_A");
    }

    @Test
    void refreshesOriginalsWhenACopyMoves() throws IOException {
        when(ragChunkService.findIndexState(any()))
                .thenReturn(Map.of("uuid-1", state("uuid-1", 4)));
        when(accessResolver.resolve(any())).thenReturn(Map.of("uuid-1",
                new RagChunkAccess(List.of("GROUP_A"), List.of(), List.of(), List.of(), List.of("coll-neu"), false, false)));

        sync.onCollectionMembershipChanged(List.of("uuid-1"));

        verify(ragChunkService).updateAccess(updatesCaptor.capture());
        assertThat(updatesCaptor.getValue().get(0).access().collections()).containsExactly("coll-neu");
    }

    @Test
    void ignoresNodesTheChunkIndexDoesNotHold() throws IOException {
        // a node the RAG tracker has not reached yet gets its access when it is first embedded
        when(ragChunkService.findIndexState(any())).thenReturn(Map.of());

        sync.onCollectionMembershipChanged(List.of("uuid-unbekannt"));

        verify(ragChunkService, never()).updateAccess(any());
        verify(accessResolver, never()).resolve(any());
    }

    @Test
    void doesNothingWithoutAnAffectedAcl() throws IOException {
        sync.onAclsChanged(List.of());

        verify(ragChunkService, never()).findNodeIdsByAclIds(any(), anyInt());
        verify(ragChunkService, never()).updateAccess(any());
    }

    @Test
    void passesTheConfiguredBoundToBothFinders() throws IOException {
        new RagAccessSync(7, ragChunkService, accessResolver).onAclsChanged(List.of(1L));

        verify(ragChunkService).findNodeIdsByAclIds(any(), org.mockito.ArgumentMatchers.eq(7));
        verify(accessResolver).findNodesWithCollectionAcl(any(), org.mockito.ArgumentMatchers.eq(7));
    }
}
