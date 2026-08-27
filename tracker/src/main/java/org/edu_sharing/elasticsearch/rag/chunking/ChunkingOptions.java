package org.edu_sharing.elasticsearch.rag.chunking;

/**
 * The knobs that decide how a document is cut up. These belong to the embedding profile, not to the
 * global tracker configuration: the model's context window is what makes a given chunk size sensible
 * in the first place, and changing any of these values invalidates every vector already written.
 *
 * @param targetTokens     size a chunk is packed towards
 * @param maxTokens        hard ceiling; no emitted chunk exceeds this
 * @param overlapTokens    how much of the previous chunk is repeated at the start of the next one
 * @param minTokens        chunks below this are merged into a neighbour where possible
 * @param maxChunksPerNode ceiling per node; the head of the document wins, the rest is reported as
 *                         dropped rather than silently discarded
 */
public record ChunkingOptions(
        int targetTokens,
        int maxTokens,
        int overlapTokens,
        int minTokens,
        int maxChunksPerNode) {

    public static final ChunkingOptions DEFAULTS = new ChunkingOptions(450, 800, 64, 80, 300);

    public ChunkingOptions {
        if (targetTokens <= 0) {
            throw new IllegalArgumentException("targetTokens must be > 0, was " + targetTokens);
        }
        if (maxTokens < targetTokens) {
            throw new IllegalArgumentException(
                    "maxTokens (" + maxTokens + ") must be >= targetTokens (" + targetTokens + ")");
        }
        if (overlapTokens < 0 || overlapTokens >= targetTokens) {
            throw new IllegalArgumentException(
                    "overlapTokens (" + overlapTokens + ") must be >= 0 and < targetTokens (" + targetTokens + ")");
        }
        if (minTokens < 0 || minTokens > targetTokens) {
            throw new IllegalArgumentException(
                    "minTokens (" + minTokens + ") must be between 0 and targetTokens (" + targetTokens + ")");
        }
        if (maxChunksPerNode <= 0) {
            throw new IllegalArgumentException("maxChunksPerNode must be > 0, was " + maxChunksPerNode);
        }
    }
}
