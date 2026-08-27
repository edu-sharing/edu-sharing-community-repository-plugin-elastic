package org.edu_sharing.elasticsearch.rag.embedding;

import java.time.Duration;

/**
 * Connection and batching settings of one embedding backend.
 * <p>
 * Part of the embedding profile rather than a global setting: {@code model} and {@code dimensions}
 * are what make an index's vectors comparable, so changing either is a profile change and means a
 * new index, never an edit to the existing one.
 *
 * @param baseUrl     endpoint root; {@code /v1/embeddings} is appended
 * @param model       model id passed to the backend, e.g. {@code BAAI/bge-m3}
 * @param dimensions  expected vector length - a mismatch fails the batch rather than writing a
 *                    vector the index cannot hold
 * @param apiKey      optional bearer token; self-hosted TEI and Ollama need none
 * @param batchSize   texts per request
 * @param timeout     per-request response timeout
 * @param maxRetries  retries after the first attempt, for rate limits and transport failures
 * @param retryDelay  first backoff step; doubles per retry
 */
public record EmbeddingProperties(
        String baseUrl,
        String model,
        int dimensions,
        String apiKey,
        int batchSize,
        Duration timeout,
        int maxRetries,
        Duration retryDelay) {

    public EmbeddingProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must be set");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must be set");
        }
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be > 0, was " + dimensions);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0, was " + batchSize);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, was " + maxRetries);
        }
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        retryDelay = retryDelay == null ? Duration.ofSeconds(1) : retryDelay;
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** Settings for a local TEI container, as documented for development. */
    public static EmbeddingProperties localTei(String baseUrl) {
        return new EmbeddingProperties(baseUrl, "BAAI/bge-m3", 1024, null, 32,
                Duration.ofSeconds(60), 3, Duration.ofSeconds(1));
    }
}
