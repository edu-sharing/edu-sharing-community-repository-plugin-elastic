package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkAccess;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateByQueryRequest;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesResponse;
import co.elastic.clients.util.ObjectBuilder;
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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The service is the only place that talks to the chunk index, so what is asserted here is the shape
 * of the requests it builds - captured and rebuilt from the lambdas it hands the client.
 * <p>
 * Three of these guard against silent data loss rather than against an exception: deterministic
 * document ids (so a replayed batch overwrites instead of duplicating), the orphan query (so a
 * shortened document does not keep answering with text it no longer contains), and the split between
 * skippable and fatal bulk failures (so an outage never advances the tracker's cursor past nodes it
 * did not actually write).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagChunkServiceTest {

    private static final String INDEX = "rag_chunks_11.0_bge-m3-v1";

    @Mock
    private ElasticsearchClient client;

    @Mock
    private ElasticsearchIndicesClient indices;

    @Captor
    private ArgumentCaptor<Function<BulkRequest.Builder, ObjectBuilder<BulkRequest>>> bulkCaptor;

    @Captor
    private ArgumentCaptor<Function<DeleteByQueryRequest.Builder, ObjectBuilder<DeleteByQueryRequest>>> deleteCaptor;

    @Captor
    private ArgumentCaptor<Function<UpdateByQueryRequest.Builder, ObjectBuilder<UpdateByQueryRequest>>> updateCaptor;

    @Captor
    private ArgumentCaptor<Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>>> searchCaptor;

    @Captor
    private ArgumentCaptor<Function<UpdateAliasesRequest.Builder, ObjectBuilder<UpdateAliasesRequest>>> aliasCaptor;

    private RagChunkService service;

    @BeforeEach
    void setUp() {
        when(client.indices()).thenReturn(indices);
        service = new RagChunkService(client, new IndexConfiguration(req -> req.index(INDEX)));
    }

    private static RagChunkDocument chunk(String nodeId, int ordinal, int chunkCount) {
        return new RagChunkDocument(nodeId, 1L, 42L, ordinal, chunkCount, "CONTENT",
                "hash-" + nodeId, "meta-" + nodeId, "Ein Abschnitt.", new float[]{0.1f, 0.2f},
                List.of("GROUP_EVERYONE"), List.of(), List.of(), List.of(), List.of(),
                false, false, "admin", "ccm:io", List.of(),
                null, List.of(), null, Map.of(), "Titel", null, 0, 14, null, null, null);
    }

    private static RagChunkMetadata metadata(String owner) {
        return new RagChunkMetadata(1L, 42L, owner, "ccm:io", List.of(), "pfad",
                List.of("pfad"), null, Map.of());
    }

    private static BulkResponse bulkResponse(BulkResponseItem... items) {
        boolean errors = List.of(items).stream().anyMatch(i -> i.error() != null);
        return BulkResponse.of(b -> b.took(1).errors(errors).items(List.of(items)));
    }

    private static BulkResponseItem item(String id, ErrorCause error, int status) {
        return BulkResponseItem.of(i -> i
                .operationType(OperationType.Index)
                .index(INDEX)
                .id(id)
                .status(status)
                .error(error));
    }

    private static ErrorCause cause(String type) {
        return ErrorCause.of(e -> e.type(type).reason("weil"));
    }

    private BulkRequest capturedBulk() throws IOException {
        verify(client).bulk(bulkCaptor.capture());
        return bulkCaptor.getValue().apply(new BulkRequest.Builder()).build();
    }

    // ---- writing ----------------------------------------------------------------------------

    @Test
    void writesOneDocumentPerChunkUnderADeterministicId() throws IOException {
        when(client.bulk(any(Function.class))).thenReturn(bulkResponse());

        service.indexChunks(List.of(chunk("uuid-1", 0, 2), chunk("uuid-1", 1, 2)));

        BulkRequest request = capturedBulk();
        assertThat(request.index()).isEqualTo(INDEX);
        assertThat(request.operations()).hasSize(2);
        assertThat(request.operations()).extracting(op -> op.index().id())
                .containsExactly("uuid-1#0", "uuid-1#1");
    }

    @Test
    void doesNotCallElasticsearchForAnEmptyBatch() throws IOException {
        assertThat(service.indexChunks(List.of())).isEmpty();

        verify(client, never()).bulk(any(Function.class));
    }

    @Test
    void reportsBackNodesWhoseDocumentElasticsearchWillNeverAccept() throws IOException {
        // a strict-mapping violation will not fix itself on retry; the batch has to continue
        when(client.bulk(any(Function.class))).thenReturn(bulkResponse(
                item("uuid-1#0", null, 200),
                item("uuid-2#0", cause("strict_dynamic_mapping_exception"), 400)));

        Set<String> failed = service.indexChunks(List.of(chunk("uuid-1", 0, 1), chunk("uuid-2", 0, 1)));

        assertThat(failed).containsExactly("uuid-2");
    }

    @Test
    void propagatesAnOutageSoTheBatchIsRetried() throws IOException {
        // swallowing this would advance the tracker's cursor past nodes that were never written
        when(client.bulk(any(Function.class))).thenReturn(bulkResponse(
                item("uuid-1#0", cause("es_rejected_execution_exception"), 429)));

        assertThatThrownBy(() -> service.indexChunks(List.of(chunk("uuid-1", 0, 1))))
                .isInstanceOf(ElasticsearchException.class);
    }

    // ---- cleaning up ------------------------------------------------------------------------

    @Test
    void deletesOnlyTheChunksBeyondTheNewLength() throws IOException {
        when(client.deleteByQuery(any(Function.class)))
                .thenReturn(DeleteByQueryResponse.of(b -> b.deleted(2L)));

        service.deleteOrphans("uuid-1", 3);

        verify(client).deleteByQuery(deleteCaptor.capture());
        DeleteByQueryRequest request = deleteCaptor.getValue().apply(new DeleteByQueryRequest.Builder()).build();
        assertThat(request.index()).containsExactly(INDEX);
        assertThat(request.conflicts()).isEqualTo(Conflicts.Proceed);
        assertThat(request.query().bool().filter()).hasSize(2);
        assertThat(request.query().bool().filter().get(0).term().field()).isEqualTo("nodeId");
        assertThat(request.query().bool().filter().get(1).range().number().field()).isEqualTo("ordinal");
        assertThat(request.query().bool().filter().get(1).range().number().gte()).isEqualTo(3.0d);
    }

    @Test
    void deletesEveryChunkOfADeletedNode() throws IOException {
        when(client.deleteByQuery(any(Function.class)))
                .thenReturn(DeleteByQueryResponse.of(b -> b.deleted(5L)));

        service.deleteByNodeIds(List.of("uuid-1", "uuid-2"));

        verify(client).deleteByQuery(deleteCaptor.capture());
        DeleteByQueryRequest request = deleteCaptor.getValue().apply(new DeleteByQueryRequest.Builder()).build();
        assertThat(request.query().terms().field()).isEqualTo("nodeId");
        assertThat(request.query().terms().terms().value()).hasSize(2);
    }

    @Test
    void skipsTheDeleteQueryWhenThereIsNothingToDelete() throws IOException {
        assertThat(service.deleteByNodeIds(List.of())).isZero();

        verify(client, never()).deleteByQuery(any(Function.class));
    }

    // ---- the skip criterion -----------------------------------------------------------------

    @Test
    void readsOneStatePerNode() throws IOException {
        when(client.search(any(Function.class), eq(RagChunkService.ChunkState.class)))
                .thenReturn(searchResponse(
                        new RagChunkService.ChunkState("uuid-1", "hash-a", "meta-a", 4),
                        new RagChunkService.ChunkState("uuid-2", "hash-b", "meta-b", 2)));

        Map<String, RagChunkService.ChunkState> state = service.findIndexState(List.of("uuid-1", "uuid-2"));

        assertThat(state).containsOnlyKeys("uuid-1", "uuid-2");
        assertThat(state.get("uuid-1").contentHash()).isEqualTo("hash-a");
        // chunkCount comes back so the caller can address the existing chunks without a second query
        assertThat(state.get("uuid-1").chunkCount()).isEqualTo(4);

        verify(client).search(searchCaptor.capture(), eq(RagChunkService.ChunkState.class));
        SearchRequest request = searchCaptor.getValue().apply(new SearchRequest.Builder()).build();
        // every chunk of a node carries the same hashes, so one hit per node is enough
        assertThat(request.collapse().field()).isEqualTo("nodeId");
        assertThat(request.source().filter().includes())
                .containsExactly("nodeId", "contentHash", "metaHash", "chunkCount");
    }

    @Test
    void treatsAnUnknownNodeAsNeedingEmbedding() throws IOException {
        when(client.search(any(Function.class), eq(RagChunkService.ChunkState.class)))
                .thenReturn(searchResponse());

        assertThat(service.findIndexState(List.of("uuid-neu"))).isEmpty();
    }

    @Test
    void neverRequestsMoreHitsThanElasticsearchWillReturn() throws IOException {
        // size may not exceed index.max_result_window (10000 by default); an oversized request is
        // rejected outright, and the rejection took the acl tracker down with it
        when(client.search(any(Function.class), eq(RagChunkService.ChunkState.class)))
                .thenReturn(searchResponse());

        List<String> many = java.util.stream.IntStream.range(0, 2500)
                .mapToObj(i -> "uuid-" + i).toList();
        service.findIndexState(many);

        verify(client, org.mockito.Mockito.atLeast(3))
                .search(searchCaptor.capture(), eq(RagChunkService.ChunkState.class));
        assertThat(searchCaptor.getAllValues()).allSatisfy(fn ->
                assertThat(fn.apply(new SearchRequest.Builder()).build().size())
                        .isLessThanOrEqualTo(1000));
    }

    @Test
    void pagesThroughTheNodesOfAnAclInsteadOfAskingForThemAtOnce() throws IOException {
        when(client.search(any(Function.class), eq(RagChunkService.ChunkState.class)))
                .thenReturn(searchResponse());

        service.findNodeIdsByAclIds(List.of(42L), 50000);

        verify(client).search(searchCaptor.capture(), eq(RagChunkService.ChunkState.class));
        SearchRequest request = searchCaptor.getValue().apply(new SearchRequest.Builder()).build();
        assertThat(request.size()).isLessThanOrEqualTo(1000);
        // total, stable sort so no chunk is seen twice or skipped while paging
        assertThat(request.sort()).hasSize(2);
    }

    @Test
    void skipsTheLookupForAnEmptyBatch() throws IOException {
        assertThat(service.findIndexState(List.of())).isEmpty();

        verify(client, never()).search(any(Function.class), eq(RagChunkService.ChunkState.class));
    }

    // ---- the cheap middle path ---------------------------------------------------------------

    @Test
    void refreshesEveryChunkOfANodeWithoutTouchingTextOrVector() throws IOException {
        when(client.bulk(any(Function.class))).thenReturn(bulkResponse());

        service.updateMetadata(List.of(
                new RagChunkService.MetadataUpdate("uuid-1", 3, metadata("admin"))));

        BulkRequest request = capturedBulk();
        assertThat(request.operations()).hasSize(3);
        assertThat(request.operations()).extracting(op -> op.update().id())
                .containsExactly("uuid-1#0", "uuid-1#1", "uuid-1#2");

        // a script, not a partial document: the mapper drops nulls, so a partial document could
        // never express a value that was cleared
        String script = request.operations().get(0).update().action().script().source();
        assertThat(script).contains("ctx._source.facets = params.m.facets");
        assertThat(script).doesNotContain("ctx._source.text").doesNotContain("ctx._source.embedding");
        // access has its own writer; sharing this script would let the two paths overwrite each other
        assertThat(script).doesNotContain("ctx._source.readers");
    }

    // ---- access -----------------------------------------------------------------------------

    @Test
    void replacesAccessOutrightRatherThanMergingIt() throws IOException {
        when(client.bulk(any(Function.class))).thenReturn(bulkResponse());

        service.updateAccess(List.of(new RagChunkService.AccessUpdate("uuid-1", 2,
                new RagChunkAccess(List.of("GROUP_A"), List.of("GROUP_B"), List.of(), List.of(),
                        List.of("coll-1"), false, false))));

        BulkRequest request = capturedBulk();
        assertThat(request.operations()).hasSize(2);
        String script = request.operations().get(0).update().action().script().source();
        // an authority that lost access must disappear, which only an assignment achieves
        assertThat(script).contains("ctx._source.readers = params.a.readers");
        assertThat(script).contains("ctx._source.collections = params.a.collections");
        // the rule's other inputs travel with it, or the query side sees a half-updated node
        assertThat(script).contains("ctx._source.collectionReaders")
                .contains("ctx._source.proposalCoordinators")
                .contains("ctx._source.restrictedAccess");
        assertThat(script).doesNotContain("addAll").doesNotContain("remove");
    }

    @Test
    void skipsTheAccessWriteWhenNothingIsAffected() throws IOException {
        assertThat(service.updateAccess(List.of())).isEmpty();

        verify(client, never()).bulk(any(Function.class));
    }

    @Test
    void skipsTheRefreshWhenNothingChanged() throws IOException {
        assertThat(service.updateMetadata(List.of())).isEmpty();

        verify(client, never()).bulk(any(Function.class));
    }

    // ---- permissions and alias --------------------------------------------------------------

    @Test
    void refreshesOnlyTheReadAuthoritiesOfAnAcl() throws IOException {
        when(client.updateByQuery(any(Function.class)))
                .thenReturn(UpdateByQueryResponse.of(b -> b.updated(9L)));

        service.updateReadPermissions(42L, List.of("GROUP_A", "GROUP_B"));

        verify(client).updateByQuery(updateCaptor.capture());
        UpdateByQueryRequest request = updateCaptor.getValue().apply(new UpdateByQueryRequest.Builder()).build();
        assertThat(request.query().term().field()).isEqualTo("aclId");
        assertThat(request.conflicts()).isEqualTo(Conflicts.Proceed);
        assertThat(request.script().source()).contains("ctx._source.permissions");
        assertThat(request.script().params()).containsKey("read");
    }

    @Test
    void movesTheAliasInASingleAtomicCall() throws IOException {
        // detach and attach have to travel together, or search sees no index in between
        when(indices.updateAliases(any(Function.class)))
                .thenReturn(UpdateAliasesResponse.of(b -> b.acknowledged(true)));

        service.pointAliasHere("rag_chunks");

        verify(indices).updateAliases(aliasCaptor.capture());
        UpdateAliasesRequest request = aliasCaptor.getValue().apply(new UpdateAliasesRequest.Builder()).build();
        assertThat(request.actions()).hasSize(2);
        assertThat(request.actions().get(0).remove().alias()).isEqualTo("rag_chunks");
        // on a first deployment there is no alias to detach yet
        assertThat(request.actions().get(0).remove().mustExist()).isFalse();
        assertThat(request.actions().get(1).add().index()).isEqualTo(INDEX);
        assertThat(request.actions().get(1).add().alias()).isEqualTo("rag_chunks");
    }

    private static SearchResponse<RagChunkService.ChunkState> searchResponse(
            RagChunkService.ChunkState... hits) {
        List<Hit<RagChunkService.ChunkState>> list = List.of(hits).stream()
                .map(h -> Hit.<RagChunkService.ChunkState>of(b -> b.index(INDEX).id(h.nodeId()).source(h)))
                .toList();
        return SearchResponse.of(b -> b
                .took(1)
                .timedOut(false)
                .shards(s -> s.total(1).successful(1).failed(0))
                .hits(h -> h
                        .hits(list)
                        .total(t -> t.value(list.size()).relation(TotalHitsRelation.Eq))));
    }
}
