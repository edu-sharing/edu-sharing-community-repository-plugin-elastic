package org.edu_sharing.elasticsearch.rag.chunking;

import java.util.List;

/**
 * The chunks for one node, plus what had to be left out.
 * <p>
 * {@code droppedChunks} exists so that hitting {@link ChunkingOptions#maxChunksPerNode()} is a
 * reportable event rather than a silent truncation - the caller is expected to record it via
 * {@code NodeFailureService} so oversized documents stay visible in the dead-letter index.
 */
public record ChunkingResult(List<Chunk> chunks, int droppedChunks) {

    public static final ChunkingResult EMPTY = new ChunkingResult(List.of(), 0);

    public ChunkingResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
    }

    public boolean truncated() {
        return droppedChunks > 0;
    }

    public boolean isEmpty() {
        return chunks.isEmpty();
    }

    public int size() {
        return chunks.size();
    }
}
