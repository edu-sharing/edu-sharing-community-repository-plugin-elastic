package org.edu_sharing.elasticsearch.rag.embedding;

/**
 * A batch could not be embedded.
 * <p>
 * Unchecked on purpose. The tracker's job on this is not to recover but to decide: record the node
 * in the dead letter index and carry on, or let the batch fail so the transaction marker is not
 * committed and the work is retried. Forcing a {@code catch} at every call site would not help it
 * make that decision any better.
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
