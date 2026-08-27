package org.edu_sharing.elasticsearch.elasticsearch.core;

import org.edu_sharing.elasticsearch.rag.chunking.Chunk;

import java.util.List;
import java.util.Map;

/**
 * One document of the chunk index, exactly as it is serialised.
 * <p>
 * A record rather than a {@code DataBuilder} tree because the index is {@code dynamic: strict}: a
 * mistyped field name has to be a compile error, not a rejected bulk item discovered in production.
 * The client's mapper is configured {@code NON_NULL}, so the many optional fields simply disappear
 * from the request when they are not set.
 * <p>
 * Flat rather than nesting {@link RagChunkMetadata}, because the mapping is flat and nesting would
 * change the JSON. {@link #of} spreads the metadata across the fields instead, which is also how it
 * is actually used: metadata is built once per node and shared by all of its chunks.
 *
 * @param nodeId      node UUID; join key back to {@code workspace_<version>._id}
 * @param ordinal     position within the node, part of the document id
 * @param chunkCount  how many chunks this node produced, used to find orphans of a longer earlier
 *                    version of the same document
 * @param contentHash text fingerprint; unchanged means the vectors are still correct
 * @param metaHash    metadata fingerprint; unchanged means not even a metadata rewrite is needed
 * @param embedding   the vector; length must match the profile's dimensions
 * @param readers     the node's own read authorities; the collection-derived fields beside it are
 *                    the remaining inputs of the read rule - see {@link RagChunkAccess}
 * @param facetsFlat  everything the metadata set defines beyond the fixed facets
 */
public record RagChunkDocument(
        String nodeId,
        Long dbid,
        Long aclId,
        int ordinal,
        int chunkCount,
        String kind,
        String contentHash,
        String metaHash,
        String text,
        float[] embedding,
        List<String> readers,
        List<String> collectionReaders,
        List<String> proposalCoordinators,
        List<String> collectionOwners,
        List<String> collections,
        boolean restrictedAccess,
        boolean restrictedReadAll,
        String owner,
        String type,
        List<String> aspects,
        String fullpath,
        List<String> fullpaths,
        Facets facets,
        Map<String, Object> facetsFlat,
        String title,
        String heading,
        Integer charStart,
        Integer charEnd,
        Integer page,
        Float timeStart,
        Float timeEnd) {

    /** Deterministic and idempotent: a replayed batch overwrites rather than duplicates. */
    public String id() {
        return documentId(nodeId, ordinal);
    }

    public static String documentId(String nodeId, int ordinal) {
        return nodeId + "#" + ordinal;
    }

    /** Combines one chunk with its node's shared metadata and vector. */
    public static RagChunkDocument of(String nodeId, Chunk chunk, int chunkCount, float[] embedding,
                                      String contentHash, RagChunkMetadata metadata,
                                      RagChunkAccess access, String title) {
        return new RagChunkDocument(
                nodeId,
                metadata.dbid(),
                metadata.aclId(),
                chunk.ordinal(),
                chunkCount,
                chunk.kind().name(),
                contentHash,
                metadata.fingerprint(),
                chunk.text(),
                embedding,
                access.readers(),
                access.collectionReaders(),
                access.proposalCoordinators(),
                access.collectionOwners(),
                access.collections(),
                access.restrictedAccess(),
                access.restrictedReadAll(),
                metadata.owner(),
                metadata.type(),
                metadata.aspects(),
                metadata.fullpath(),
                metadata.fullpaths(),
                metadata.facets(),
                metadata.facetsFlat(),
                title,
                chunk.heading(),
                chunk.charStart(),
                chunk.charEnd(),
                chunk.page(),
                chunk.timeStart() == null ? null : chunk.timeStart().floatValue(),
                chunk.timeEnd() == null ? null : chunk.timeEnd().floatValue());
    }

    /** The fixed, filterable facets. Anything schema-unstable belongs in {@code facetsFlat}. */
    public record Facets(
            List<String> subject,
            List<String> educationalContext,
            List<String> learningResourceType,
            String license,
            String language,
            String source,
            String mimetype,
            String createdAt,
            String modifiedAt) {
    }
}
