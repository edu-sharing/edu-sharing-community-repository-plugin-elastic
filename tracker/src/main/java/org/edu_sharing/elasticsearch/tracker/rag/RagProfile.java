package org.edu_sharing.elasticsearch.tracker.rag;

import org.edu_sharing.elasticsearch.rag.chunking.ChunkingOptions;
import org.edu_sharing.elasticsearch.rag.embedding.EmbeddingProperties;

import java.time.Duration;
import java.util.Locale;

/**
 * One embedding profile: everything whose change invalidates the vectors already written.
 * <p>
 * Model, dimensions, similarity and the chunking parameters travel together because they only make
 * sense together - a vector produced by one model means nothing in the space of another, and the
 * chunk boundaries depend on the model's context window. That is why a change here is never an edit
 * to the existing index but a second index built alongside it, with the alias deciding which one
 * search reads.
 * <p>
 * {@code id} is set by hand rather than derived from the settings. Deriving it would mean an
 * absent-minded change to {@code targetTokens} silently triggers a full rebuild of the corpus.
 *
 * @param id               profile id; also the suffix of its index and of its tracker's cursor
 * @param active           whether the alias points here - exactly one profile may set this
 * @param maxChunksPerNode ceiling per node, reported rather than silently applied
 */
public record RagProfile(
        String id,
        boolean active,
        String model,
        Integer dimensions,
        String similarity,
        String baseUrl,
        String apiKey,
        Integer batchSize,
        Duration timeout,
        Integer maxRetries,
        Duration retryDelay,
        Integer maxChunksPerNode,
        Chunk chunk) {

    public RagProfile {
        require(id, "id");
        require(model, "model");
        require(baseUrl, "baseUrl");
        if (dimensions == null || dimensions <= 0) {
            // no sensible default exists: the value has to match the model, and guessing it wrong
            // is only noticed when the first bulk write is rejected
            throw new IllegalArgumentException(
                    "tracker.rag.profiles[" + id + "].dimensions must be set to the model's vector length");
        }
        similarity = similarity == null || similarity.isBlank() ? "cosine" : similarity;
        batchSize = batchSize == null ? 32 : batchSize;
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        maxRetries = maxRetries == null ? 3 : maxRetries;
        retryDelay = retryDelay == null ? Duration.ofSeconds(1) : retryDelay;
        maxChunksPerNode = maxChunksPerNode == null ? 300 : maxChunksPerNode;
        chunk = chunk == null ? new Chunk(null, null, null, null) : chunk;
    }

    public ChunkingOptions toChunkingOptions() {
        return new ChunkingOptions(chunk.targetTokens(), chunk.maxTokens(),
                chunk.overlapTokens(), chunk.minTokens(), maxChunksPerNode);
    }

    public EmbeddingProperties toEmbeddingProperties() {
        return new EmbeddingProperties(baseUrl, model, dimensions, apiKey, batchSize,
                timeout, maxRetries, retryDelay);
    }

    /** Suffix for the index and the tracker bean, reduced to what both can safely carry. */
    public String slug() {
        return id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("tracker.rag.profiles[].".concat(field) + " must be set");
        }
    }

    /** Chunk sizes, in estimated tokens. Defaults are the ones documented for {@code bge-m3}. */
    public record Chunk(Integer targetTokens, Integer maxTokens, Integer overlapTokens, Integer minTokens) {
        public Chunk {
            targetTokens = targetTokens == null ? 450 : targetTokens;
            maxTokens = maxTokens == null ? 800 : maxTokens;
            overlapTokens = overlapTokens == null ? 64 : overlapTokens;
            minTokens = minTokens == null ? 80 : minTokens;
        }
    }
}
