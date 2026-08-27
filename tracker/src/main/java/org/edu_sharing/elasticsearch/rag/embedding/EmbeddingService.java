package org.edu_sharing.elasticsearch.rag.embedding;

import java.util.List;

/**
 * Turns chunk text into vectors.
 * <p>
 * The whole reason this is an interface is that the model has to be replaceable without touching the
 * tracker: which model produces the vectors is a property of the embedding profile, and a profile
 * change means a new index built alongside the old one.
 */
public interface EmbeddingService {

    /**
     * Embeds every text, in order.
     * <p>
     * Implementations must return exactly one vector per input, at the same position - the caller
     * pairs result <em>i</em> with chunk <em>i</em> and has no way to detect a shift. Batching,
     * retrying and any reordering the wire protocol requires happen behind this method.
     *
     * @throws EmbeddingException if the texts could not be embedded after the configured retries
     */
    List<float[]> embed(List<String> texts);

    /**
     * Vector length this service produces. Must match the {@code dims} of the profile's index -
     * cross-checking the two at startup turns a misconfiguration into a clear error instead of a
     * mapping rejection on the first bulk write.
     */
    int dimensions();

    /** Model identifier, for logging and for the index name of the profile. */
    String model();
}
