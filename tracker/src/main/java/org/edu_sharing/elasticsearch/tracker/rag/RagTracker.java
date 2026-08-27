package org.edu_sharing.elasticsearch.tracker.rag;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.edu_sharing.elasticsearch.alfresco.client.FetchParameters;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeData;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.elasticsearch.core.NodeFailureService;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkAccess;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkDocument;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkMetadata;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkService;
import org.edu_sharing.elasticsearch.rag.chunking.Chunk;
import org.edu_sharing.elasticsearch.rag.chunking.ChunkSource;
import org.edu_sharing.elasticsearch.rag.chunking.ChunkingResult;
import org.edu_sharing.elasticsearch.rag.chunking.ChunkingService;
import org.edu_sharing.elasticsearch.rag.chunking.ContentFingerprint;
import org.edu_sharing.elasticsearch.rag.embedding.EmbeddingException;
import org.edu_sharing.elasticsearch.rag.embedding.EmbeddingService;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.core.AbstractAlfTransactionTracker;
import org.edu_sharing.elasticsearch.tracker.utils.Partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the chunk index in step with the repository.
 * <p>
 * Built like {@code ContentTracker} - its own transaction cursor, {@code dependsOn=mainTracker}, the
 * same full-text fetch - with one difference that matters: the text is not truncated. The chunk
 * index exists to answer questions about what a document says, and
 * {@code tracker.content.maxContentLength} was chosen for a single {@code content.fulltext} field.
 * <p>
 * Each node takes one of three paths, decided by two hashes:
 * <ul>
 *   <li>text fingerprint changed - re-chunk and re-embed, the expensive one</li>
 *   <li>text unchanged, metadata fingerprint changed - rewrite the filter fields only, no model call</li>
 *   <li>both unchanged - nothing at all</li>
 * </ul>
 * The third case is the common one. ACL, collection and statistics changes touch nodes constantly
 * without changing anything this index stores, and absorbing those is what makes the tracker
 * affordable to run continuously.
 */
@Slf4j
public class RagTracker extends AbstractAlfTransactionTracker<RagTrackerProperties> {

    /**
     * Collection copies carry the original's metadata shell. Embedding them would put the same title
     * into the index once per collection membership - duplicate hits, and embedding paid for twice.
     * <p>
     * Filtered here rather than through the webscript's {@code excludeAspects} parameter: that
     * parameter answers 500 for this aspect, and {@code getNodes} does not check the status code, so
     * the failure arrives as an empty node list with nothing in the log. Filtering on the aspects we
     * already fetched cannot fail that way.
     */
    static final String COLLECTION_COPY_ASPECT = "ccm:collection_io_reference";

    private final RagProfile profile;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final RagChunkService ragChunkService;
    private final RagAccessResolver accessResolver;

    public RagTracker(RagTrackerProperties ragTrackerProperties,
                      RagProfile profile,
                      ChunkingService chunkingService,
                      EmbeddingService embeddingService,
                      RagChunkService ragChunkService,
                      RagAccessResolver accessResolver) {
        super(ragTrackerProperties);
        this.profile = profile;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.ragChunkService = ragChunkService;
        this.accessResolver = accessResolver;
    }

    @Override
    public void trackNodes(List<Node> nodes) throws IOException {
        List<String> deleted = nodes.stream()
                .filter(node -> "d".equals(node.getStatus()))
                .map(node -> Tools.getUUID(node.getNodeRef()))
                .distinct()
                .toList();
        if (!deleted.isEmpty()) {
            ragChunkService.deleteByNodeIds(deleted);
        }

        List<Node> live = filterIndexableNodes(nodes);
        if (live.isEmpty()) {
            return;
        }

        for (List<Node> partition : Partition.getPartitions(live, props.getBulkSizeElastic())) {
            trackPartition(partition);
        }
    }

    private void trackPartition(List<Node> partition) throws IOException {
        indexNodes(fetch(partition));
    }

    /**
     * The three-path decision, separated from the Alfresco fetch so it can be exercised on its own -
     * the fetch and the transaction cursor belong to {@link AbstractAlfTransactionTracker}.
     */
    void indexNodes(List<NodeData> nodeData) throws IOException {
        if (nodeData.isEmpty()) {
            return;
        }

        Map<String, NodeState> wanted = new LinkedHashMap<>();
        for (NodeData data : nodeData) {
            if (isCollectionCopy(data)) {
                continue;
            }
            ChunkSource source = RagNodeMapper.toChunkSource(data);
            wanted.put(source.nodeId(), new NodeState(data, source,
                    ContentFingerprint.of(source), RagNodeMapper.toMetadata(data)));
        }

        Map<String, RagChunkService.ChunkState> indexed = ragChunkService.findIndexState(wanted.keySet());
        // who may see a node depends on the collections holding a copy of it, which the workspace
        // index knows and NodeData does not
        Map<String, RagChunkAccess> access = accessResolver.resolve(wanted.keySet());

        List<RagChunkService.MetadataUpdate> refresh = new ArrayList<>();
        List<NodeState> embed = new ArrayList<>();
        int untouched = 0;
        for (NodeState state : wanted.values()) {
            RagChunkService.ChunkState existing = indexed.get(state.nodeId());
            if (existing == null || !state.contentHash.equals(existing.contentHash())) {
                embed.add(state);
            } else if (!state.metadata.fingerprint().equals(existing.metaHash())) {
                refresh.add(new RagChunkService.MetadataUpdate(
                        state.nodeId(), existing.chunkCount(), state.metadata));
            } else {
                untouched++;
            }
        }

        Set<String> refreshFailures = ragChunkService.updateMetadata(refresh);
        int written = embedAndIndex(embed, indexed, access);

        log.info("rag: nodes={} embedded={} metadataOnly={} unchanged={} refreshFailures={}",
                wanted.size(), written, refresh.size(), untouched, refreshFailures.size());
    }

    /**
     * Chunks, embeds and writes the nodes whose text changed.
     * <p>
     * Embedding happens per node so a single unusable document can be recorded and skipped rather
     * than stalling the tracker forever - the client does not retry a 400, so a node the model
     * rejects would otherwise be replayed on every run. If <em>every</em> node of the batch fails,
     * that is an outage rather than bad data, and the failure is propagated so the transaction marker
     * is not committed.
     */
    private int embedAndIndex(List<NodeState> states, Map<String, RagChunkService.ChunkState> indexed,
                              Map<String, RagChunkAccess> access) throws IOException {
        if (states.isEmpty()) {
            return 0;
        }

        List<RagChunkDocument> documents = new ArrayList<>();
        Map<String, Integer> chunkCounts = new LinkedHashMap<>();
        List<String> emptied = new ArrayList<>();
        EmbeddingException lastFailure = null;
        int failed = 0;

        for (NodeState state : states) {
            ChunkingResult result = chunkingService.chunk(state.source, profile.toChunkingOptions());
            if (result.isEmpty()) {
                // nothing left to say about this node - drop whatever it had before
                emptied.add(state.nodeId());
                continue;
            }
            if (result.truncated()) {
                recordTruncation(state, result);
            }

            try {
                List<float[]> vectors = embeddingService.embed(
                        result.chunks().stream().map(Chunk::embeddingText).toList());
                for (int i = 0; i < result.chunks().size(); i++) {
                    documents.add(RagChunkDocument.of(state.nodeId(), result.chunks().get(i),
                            result.size(), vectors.get(i), state.contentHash, state.metadata,
                            access.getOrDefault(state.nodeId(), RagChunkAccess.NONE),
                            state.source.title()));
                }
                chunkCounts.put(state.nodeId(), result.size());
            } catch (EmbeddingException e) {
                failed++;
                lastFailure = e;
                log.warn("could not embed node {}: {}", state.nodeId(), e.getMessage());
                recordFailure(state, e);
            }
        }

        if (failed == states.size() && lastFailure != null) {
            throw new EmbeddingException("every node of this batch failed to embed - treating this "
                    + "as an outage rather than as bad data, so the batch is retried", lastFailure);
        }

        if (!emptied.isEmpty()) {
            ragChunkService.deleteByNodeIds(emptied);
        }

        Set<String> rejected = ragChunkService.indexChunks(documents);
        for (Map.Entry<String, Integer> entry : chunkCounts.entrySet()) {
            if (rejected.contains(entry.getKey())) {
                continue;
            }
            // only worth a query when the node actually got shorter
            RagChunkService.ChunkState existing = indexed.get(entry.getKey());
            if (existing != null && existing.chunkCount() > entry.getValue()) {
                ragChunkService.deleteOrphans(entry.getKey(), entry.getValue());
            }
        }
        return chunkCounts.size() - rejected.size();
    }

    private static boolean isCollectionCopy(NodeData data) {
        NodeMetadata node = data.getNodeMetadata();
        return node != null && node.getAspects() != null
                && node.getAspects().contains(COLLECTION_COPY_ASPECT);
    }

    private void recordTruncation(NodeState state, ChunkingResult result) {
        NodeMetadata node = state.data.getNodeMetadata();
        nodeFailureService.record(new NodeFailureService.NodeFailure(getName(), getName(), "chunk",
                        node.getId(), node.getNodeRef(), node.getType(), node.getTxnId()),
                "chunk_limit_exceeded",
                "document exceeds maxChunksPerNode, " + result.droppedChunks() + " chunks dropped");
    }

    private void recordFailure(NodeState state, EmbeddingException failure) {
        NodeMetadata node = state.data.getNodeMetadata();
        nodeFailureService.record(new NodeFailureService.NodeFailure(getName(), getName(), "embed",
                        node.getId(), node.getNodeRef(), node.getType(), node.getTxnId()),
                "embedding_failed", failure.getMessage());
    }

    /**
     * Metadata plus full text, fetched the way {@code ContentTracker} does it - minus its
     * truncation.
     */
    private List<NodeData> fetch(List<Node> nodes) throws IOException {
        Collection<List<Node>> partitions = Partition.getPartitions(nodes, props.getFetchSizeAlfresco());
        List<NodeMetadata> metadata = Collections.synchronizedList(new ArrayList<>());
        threadUtil.runThreaded(partitions.stream().toList(),
                p -> metadata.addAll(alfClient.getNodeMetadata(p)), true, true);

        List<NodeData> nodeData = Collections.synchronizedList(new ArrayList<>());
        threadUtil.runThreaded(
                Partition.getPartitions(metadata, props.getFetchSizeAlfresco()).stream().toList(),
                p -> {
                    List<NodeData> fetched = alfClient.getNodeData(p, FetchParameters.MINIMAL);
                    for (NodeData data : fetched) {
                        data.setFullText(fullTextOf(data));
                    }
                    nodeData.addAll(fetched);
                }, true, true);

        // labels for the context header and the metadata chunk; without them the facets are URIs
        threadUtil.runThreaded(nodeData, eduSharingService::translateValuespaceProps, false, false);
        return nodeData;
    }

    private String fullTextOf(NodeData nodeData) {
        String fullText = null;
        try {
            if (RagTrackerProperties.Api.Alfresco.equals(props.getApi())) {
                fullText = alfClient.getTextContent(nodeData.getNodeMetadata().getId());
            } else {
                fullText = eduSharingService.getTextContent(
                        Tools.getUUID(nodeData.getNodeMetadata().getNodeRef()));
            }
        } catch (Throwable t) {
            log.warn("could not fetch text content for {}",
                    nodeData.getNodeMetadata().getNodeRef(), t);
        }
        if (fullText != null && props.getMaxContentLength() > 0
                && fullText.length() > props.getMaxContentLength()) {
            log.info("node {} has {} characters of text, cutting at {}",
                    nodeData.getNodeMetadata().getNodeRef(), fullText.length(),
                    props.getMaxContentLength());
            fullText = fullText.substring(0, props.getMaxContentLength());
        }
        return StringUtils.isBlank(fullText) ? null : fullText;
    }

    /** One node, its chunk source and both fingerprints, carried through the three paths. */
    private record NodeState(NodeData data, ChunkSource source, String contentHash,
                             RagChunkMetadata metadata) {
        String nodeId() {
            return source.nodeId();
        }
    }
}
