package org.edu_sharing.elasticsearch.rag.chunking;

/**
 * What a chunk was derived from. Consumers should branch on this rather than on {@code ordinal}:
 * the metadata chunk is only emitted when the node actually carries metadata, so ordinal 0 is not
 * guaranteed to be one.
 */
public enum ChunkKind {
    /** Title, description, keywords and the didactic facets as running text. */
    METADATA,
    /** A slice of the extracted full text. */
    CONTENT
}
