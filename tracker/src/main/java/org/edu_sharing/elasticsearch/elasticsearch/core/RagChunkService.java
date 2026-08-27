package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.UpdateByQueryResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import co.elastic.clients.json.JsonData;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.utils.ElasticErrorClassifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The only class that knows the chunk index exists.
 * <p>
 * Everything above it - chunking, embedding - stays free of Elasticsearch, and the tracker talks to
 * this instead of to the client. Structurally the counterpart of {@link AuthorityService}: bound to
 * one index via its own {@link IndexConfiguration}, with its own bulk writer, while
 * {@link WorkspaceService} owns the workspace index.
 * <p>
 * One instance per embedding profile. The profile decides the physical index name, and the alias
 * decides which of them search actually reads.
 */
@Slf4j
public class RagChunkService {

    /**
     * Assigns each field wholesale so that a value which disappeared is actually removed. Kept as a
     * constant so Elasticsearch can cache the compiled script across the whole batch.
     */
    private static final String METADATA_SCRIPT = String.join("\n",
            "ctx._source.dbid = params.m.dbid;",
            "ctx._source.aclId = params.m.aclId;",
            "ctx._source.owner = params.m.owner;",
            "ctx._source.type = params.m.type;",
            "ctx._source.aspects = params.m.aspects;",
            "ctx._source.fullpath = params.m.fullpath;",
            "ctx._source.fullpaths = params.m.fullpaths;",
            "ctx._source.facets = params.m.facets;",
            "ctx._source.facetsFlat = params.m.facetsFlat;",
            "ctx._source.metaHash = params.metaHash;");

    /**
     * Replaces the access fields outright. Nothing is merged or subtracted: the caller always passes
     * a freshly resolved union, so an authority that lost access simply is not in the new list.
     */
    private static final String ACCESS_SCRIPT = String.join("\n",
            "ctx._source.readers = params.a.readers;",
            "ctx._source.collectionReaders = params.a.collectionReaders;",
            "ctx._source.proposalCoordinators = params.a.proposalCoordinators;",
            "ctx._source.collectionOwners = params.a.collectionOwners;",
            "ctx._source.collections = params.a.collections;",
            "ctx._source.restrictedAccess = params.a.restrictedAccess;",
            "ctx._source.restrictedReadAll = params.a.restrictedReadAll;");

    /** One page of hits; also the ceiling for a single terms lookup. */
    private static final int PAGE_SIZE = 1000;

    private final ElasticsearchClient client;

    @Getter
    private final String index;

    public RagChunkService(ElasticsearchClient client, IndexConfiguration ragChunks) {
        this.client = client;
        this.index = ragChunks.getIndex();
    }

    /**
     * Writes the chunks of one batch of nodes.
     * <p>
     * Failures are split the way {@link WorkspaceService#index} splits them: a document Elasticsearch
     * will never accept is reported back so the caller can record it and move on, while anything else
     * - a rejected bulk queue, an unavailable shard - is thrown, so the tracker does not commit its
     * transaction marker and retries the batch.
     *
     * @return node ids whose chunks could not be written and should not count as indexed
     */
    public Set<String> indexChunks(List<RagChunkDocument> documents) throws IOException {
        if (documents.isEmpty()) {
            return Set.of();
        }

        List<BulkOperation> operations = new ArrayList<>(documents.size());
        Map<String, String> nodeIdByDocumentId = new HashMap<>();
        for (RagChunkDocument document : documents) {
            nodeIdByDocumentId.put(document.id(), document.nodeId());
            operations.add(BulkOperation.of(op -> op.index(i -> i
                    .index(index)
                    .id(document.id())
                    .document(document))));
        }

        BulkResponse response = client.bulk(req -> req.index(index).operations(operations));
        if (!response.errors()) {
            log.debug("indexed {} chunks for {} nodes", documents.size(), nodeIdByDocumentId.size());
            return Set.of();
        }

        Set<String> failedNodes = new LinkedHashSet<>();
        ElasticsearchException fatal = null;
        for (BulkResponseItem item : response.items()) {
            if (item.error() == null) {
                continue;
            }
            String nodeId = nodeIdByDocumentId.get(item.id());
            if (ElasticErrorClassifier.isNodeLevel(item.status(), item.error())) {
                log.warn("skipping chunk {}: [{}] {}", item.id(),
                        ElasticErrorClassifier.errorType(item.error()), item.error().reason());
                failedNodes.add(nodeId);
                continue;
            }
            log.error("bulk indexing of chunk {} failed: [{}] {}", item.id(),
                    ElasticErrorClassifier.errorType(item.error()), item.error().reason());
            if (fatal == null) {
                // keep going so every broken chunk of this batch is logged, then fail
                fatal = ElasticErrorClassifier.toException("es/bulk", item.status(), item.error());
            }
        }
        if (fatal != null) {
            throw fatal;
        }
        return failedNodes;
    }

    /**
     * Removes chunks left over from a longer earlier version of the same node.
     * <p>
     * Without this, replacing a document with a shorter one leaves its surplus chunks in the index
     * forever: they keep their vectors, keep matching queries, and cite text that is no longer in the
     * document. Writing with deterministic ids overwrites the first {@code chunkCount} chunks but
     * cannot know about the ones beyond them.
     */
    public long deleteOrphans(String nodeId, int chunkCount) throws IOException {
        DeleteByQueryResponse response = client.deleteByQuery(req -> req
                .index(index)
                .conflicts(Conflicts.Proceed)
                .query(q -> q.bool(b -> b
                        .filter(f -> f.term(t -> t.field("nodeId").value(nodeId)))
                        .filter(f -> f.range(r -> r.number(n -> n.field("ordinal").gte((double) chunkCount)))))));
        logDeletion("orphans of " + nodeId, response);
        return response.deleted() == null ? 0L : response.deleted();
    }

    /** Drops every chunk of the given nodes, for nodes deleted in the repository. */
    public long deleteByNodeIds(Collection<String> nodeIds) throws IOException {
        if (nodeIds.isEmpty()) {
            return 0L;
        }
        List<FieldValue> values = nodeIds.stream().map(FieldValue::of).toList();
        DeleteByQueryResponse response = client.deleteByQuery(req -> req
                .index(index)
                .conflicts(Conflicts.Proceed)
                .query(q -> q.terms(t -> t.field("nodeId").terms(v -> v.value(values)))));
        logDeletion(nodeIds.size() + " nodes", response);
        return response.deleted() == null ? 0L : response.deleted();
    }

    /**
     * What the index currently holds per node, for the nodes that have chunks at all.
     * <p>
     * Every chunk of a node carries the same hashes and the same count, so the query collapses to one
     * hit per node. Missing nodes are simply absent - the caller treats that as "must be embedded".
     */
    public Map<String, ChunkState> findIndexState(Collection<String> nodeIds) throws IOException {
        if (nodeIds.isEmpty()) {
            return Map.of();
        }
        Map<String, ChunkState> state = new HashMap<>();
        // Chunked because `size` may not exceed index.max_result_window (10000 by default) - a
        // single oversized request does not degrade, it is rejected outright.
        List<String> all = List.copyOf(nodeIds);
        for (int from = 0; from < all.size(); from += PAGE_SIZE) {
            List<FieldValue> values = all.subList(from, Math.min(from + PAGE_SIZE, all.size()))
                    .stream().map(FieldValue::of).toList();
            state.putAll(collapseByNode(
                    Query.of(q -> q.terms(t -> t.field("nodeId").terms(v -> v.value(values)))),
                    values.size()));
        }
        return state;
    }

    private Map<String, ChunkState> collapseByNode(Query query, int size) throws IOException {
        SearchResponse<ChunkState> response = client.search(req -> req
                        .index(index)
                        .size(size)
                        .query(query)
                        .collapse(c -> c.field("nodeId"))
                        .source(s -> s.filter(f -> f.includes("nodeId", "contentHash", "metaHash", "chunkCount"))),
                ChunkState.class);

        Map<String, ChunkState> state = new HashMap<>();
        for (Hit<ChunkState> hit : response.hits().hits()) {
            ChunkState source = hit.source();
            if (source != null && source.nodeId() != null) {
                state.put(source.nodeId(), source);
            }
        }
        return state;
    }

    /**
     * Rewrites the filterable fields of a node's chunks without touching text or vector.
     * <p>
     * This is the middle path between doing nothing and re-embedding: a corrected licence, a move, a
     * new collection membership all have to reach the index so filters stay right, and none of them
     * is a reason to call the model again. It is still a full document rewrite in Lucene, which is
     * why the caller only reaches it when {@link RagChunkMetadata#fingerprint()} actually changed.
     * <p>
     * A script rather than a partial document on purpose: a partial document cannot express removal,
     * because the client's mapper drops nulls, so a licence that was cleared would keep its old
     * value forever. Assigning the whole sub-object replaces it, absent keys included.
     *
     * @return node ids whose chunks could not be updated
     */
    public Set<String> updateMetadata(List<MetadataUpdate> updates) throws IOException {
        if (updates.isEmpty()) {
            return Set.of();
        }
        List<BulkOperation> operations = new ArrayList<>();
        Map<String, String> nodeIdByDocumentId = new HashMap<>();
        for (MetadataUpdate update : updates) {
            JsonData metadata = JsonData.of(update.metadata());
            String metaHash = update.metadata().fingerprint();
            for (int ordinal = 0; ordinal < update.chunkCount(); ordinal++) {
                String documentId = RagChunkDocument.documentId(update.nodeId(), ordinal);
                nodeIdByDocumentId.put(documentId, update.nodeId());
                operations.add(BulkOperation.of(op -> op.update(u -> u
                        .index(index)
                        .id(documentId)
                        .action(a -> a.script(sc -> sc
                                .source(METADATA_SCRIPT)
                                .params("m", metadata)
                                .params("metaHash", JsonData.of(metaHash)))))));
            }
        }

        BulkResponse response = client.bulk(req -> req.index(index).operations(operations));
        if (!response.errors()) {
            return Set.of();
        }
        Set<String> failed = new LinkedHashSet<>();
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null) {
                log.warn("metadata refresh of {} failed: [{}] {}", item.id(),
                        ElasticErrorClassifier.errorType(item.error()), item.error().reason());
                failed.add(nodeIdByDocumentId.get(item.id()));
            }
        }
        return failed;
    }

    /**
     * Writes the freshly resolved access of a node's chunks.
     * <p>
     * Lucene has no partial update, so this rewrites each whole document, vector included, and forces
     * an HNSW rebuild on the next merge - by far the most expensive write in this index. The caller
     * is therefore expected to reach it only when the union actually changed.
     *
     * @return node ids whose chunks could not be updated
     */
    public Set<String> updateAccess(List<AccessUpdate> updates) throws IOException {
        if (updates.isEmpty()) {
            return Set.of();
        }
        List<BulkOperation> operations = new ArrayList<>();
        Map<String, String> nodeIdByDocumentId = new HashMap<>();
        for (AccessUpdate update : updates) {
            JsonData access = JsonData.of(update.access());
            for (int ordinal = 0; ordinal < update.chunkCount(); ordinal++) {
                String documentId = RagChunkDocument.documentId(update.nodeId(), ordinal);
                nodeIdByDocumentId.put(documentId, update.nodeId());
                operations.add(BulkOperation.of(op -> op.update(u -> u
                        .index(index)
                        .id(documentId)
                        .action(a -> a.script(sc -> sc
                                .source(ACCESS_SCRIPT)
                                .params("a", access))))));
            }
        }

        BulkResponse response = client.bulk(req -> req.index(index).operations(operations));
        if (!response.errors()) {
            log.info("refreshed access on {} chunks of {} nodes", operations.size(), updates.size());
            return Set.of();
        }
        Set<String> failed = new LinkedHashSet<>();
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null) {
                log.warn("access refresh of {} failed: [{}] {}", item.id(),
                        ElasticErrorClassifier.errorType(item.error()), item.error().reason());
                failed.add(nodeIdByDocumentId.get(item.id()));
            }
        }
        return failed;
    }

    /**
     * Node ids whose chunks carry one of these acl ids - the node's own ACL.
     * <p>
     * Paged with {@code search_after} rather than a single large {@code size}: the number of nodes
     * under one ACL is unbounded, and a request past {@code index.max_result_window} is rejected,
     * not merely slow. Sorting on {@code nodeId} plus {@code ordinal} is total and stable, so no
     * document is seen twice or skipped.
     */
    public Set<String> findNodeIdsByAclIds(Collection<Long> aclIds, int maxNodes) throws IOException {
        if (aclIds.isEmpty()) {
            return Set.of();
        }
        List<FieldValue> values = aclIds.stream().map(FieldValue::of).toList();
        Query query = Query.of(q -> q.terms(t -> t.field("aclId").terms(v -> v.value(values))));

        Set<String> nodeIds = new LinkedHashSet<>();
        List<FieldValue> after = null;
        while (nodeIds.size() < maxNodes) {
            final List<FieldValue> searchAfter = after;
            SearchResponse<ChunkState> response = client.search(req -> {
                req.index(index)
                        .size(PAGE_SIZE)
                        .query(query)
                        .sort(so -> so.field(f -> f.field("nodeId").order(SortOrder.Asc)))
                        .sort(so -> so.field(f -> f.field("ordinal").order(SortOrder.Asc)))
                        .source(so -> so.filter(f -> f.includes("nodeId")));
                if (searchAfter != null) {
                    req.searchAfter(searchAfter);
                }
                return req;
            }, ChunkState.class);

            List<Hit<ChunkState>> hits = response.hits().hits();
            if (hits.isEmpty()) {
                break;
            }
            for (Hit<ChunkState> hit : hits) {
                if (hit.source() != null && hit.source().nodeId() != null) {
                    nodeIds.add(hit.source().nodeId());
                }
            }
            after = hits.get(hits.size() - 1).sort();
        }
        if (nodeIds.size() >= maxNodes) {
            log.warn("stopping at {} nodes for acl refresh - the rest keeps its previous access "
                    + "until the node is touched again", maxNodes);
        }
        return nodeIds;
    }

    /**
     * Refreshes the read authorities of every chunk under an ACL, without touching its vector's
     * value.
     * <p>
     * Lucene has no partial update, so this rewrites each whole document, vector included, and forces
     * an HNSW rebuild on the next merge - by far the most expensive write in this index. Two things
     * follow, and both belong to the caller: only call this when the read authorities actually
     * changed (ACL change sets fire often without touching them), and run it on a slower schedule
     * than the workspace equivalent.
     */
    public long updateReadPermissions(long aclId, List<String> readers) throws IOException {
        UpdateByQueryResponse response = client.updateByQuery(req -> req
                .index(index)
                .query(q -> q.term(t -> t.field("aclId").value(aclId)))
                .conflicts(Conflicts.Proceed)
                .refresh(false)
                .script(s -> s
                        .source("ctx._source.permissions = ['read': params.read]")
                        .params("read", JsonData.of(readers))));

        response.failures().forEach(failure -> log.error(failure.cause().toString(), failure.cause()));
        long updated = response.updated() == null ? 0L : response.updated();
        log.info("acl {}: refreshed read permissions on {} chunks", aclId, updated);
        return updated;
    }

    /**
     * Points the search alias at this index, atomically.
     * <p>
     * This is the whole model switch: one call that detaches the alias from whichever index held it
     * and attaches it here, with no moment in between where search sees nothing. Running it in the
     * other direction is the rollback, for as long as the previous index still exists.
     */
    public void pointAliasHere(String alias) throws IOException {
        client.indices().updateAliases(req -> req.actions(
                // mustExist(false): on the very first deployment there is no alias to detach yet
                Action.of(a -> a.remove(r -> r.index("*").alias(alias).mustExist(false))),
                Action.of(a -> a.add(add -> add.index(index).alias(alias)))));
        log.info("alias {} now points at {}", alias, index);
    }

    /**
     * What the live index says produced its vectors, or null for an index written before this was
     * recorded. Read from the mapping rather than from configuration, so it describes the index
     * actually in use even after an alias switch.
     */
    public RagIndexMetadata readMetadata() throws IOException {
        return RagIndexMetadata.fromMeta(client.indices().getMapping(g -> g.index(index))
                .result().get(index).mappings().meta());
    }

    public void refresh() throws IOException {
        client.indices().refresh(req -> req.index(index));
    }

    private void logDeletion(String what, DeleteByQueryResponse response) {
        response.failures().forEach(failure -> log.error(failure.cause().toString(), failure.cause()));
        if (response.deleted() != null && response.deleted() > 0) {
            log.debug("deleted {} chunks ({})", response.deleted(), what);
        }
    }

    /** What {@link #findIndexState} reads back per node. */
    public record ChunkState(String nodeId, String contentHash, String metaHash, int chunkCount) {
    }

    /** One node's chunks to refresh, and how many of them there are. */
    public record MetadataUpdate(String nodeId, int chunkCount, RagChunkMetadata metadata) {
    }

    /** One node's chunks whose access has to be rewritten. */
    public record AccessUpdate(String nodeId, int chunkCount, RagChunkAccess access) {
    }

    /** Builds a query matching every chunk of one node - shared by callers that need it. */
    static Query byNodeId(String nodeId) {
        return Query.of(q -> q.term(t -> t.field("nodeId").value(nodeId)));
    }
}
