package org.edu_sharing.elasticsearch.rag.chunking;

import java.util.List;

/**
 * Turns normalized document text into {@link Piece}s. One implementation per content shape; the
 * {@link ChunkingService} asks each in turn and uses the first that claims the document.
 * <p>
 * Implementations decide <em>where</em> a document may be cut and what the provenance of each piece
 * is. Deciding how large a chunk ends up being is not their job - that belongs to
 * {@link ChunkPacker}, so that chunk sizes stay uniform no matter which strategy produced the
 * pieces.
 */
interface Chunker {

    /**
     * @param mimetype       {@code content.mimetype}, may be null
     * @param normalizedText the text itself, so a chunker can recognise a format the mimetype does
     *                       not admit to - transcripts in particular arrive as {@code text/plain}
     *                       often enough that sniffing is worth it
     */
    boolean supports(String mimetype, String normalizedText);

    List<Piece> segment(String normalizedText, ChunkingOptions options);
}
