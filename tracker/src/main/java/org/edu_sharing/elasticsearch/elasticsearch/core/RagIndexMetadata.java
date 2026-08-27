package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.json.JsonData;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What produced the vectors in a chunk index, written into the mapping's {@code _meta}.
 * <p>
 * The query side has to embed the user's question with <em>exactly</em> the same model, dimensions
 * and normalisation as the chunks, or the two vectors are not comparable. Nothing fails when they
 * differ - retrieval simply gets quietly worse, which is the kind of defect that survives for
 * months.
 * <p>
 * Kept here rather than in shared configuration on purpose: configuration on two sides can drift,
 * and after an alias switch the answer changes anyway. Resolving alias to index to {@code _meta}
 * always describes the index actually being searched, and costs nothing per document - which is why
 * this is not a field on {@link RagChunkDocument}, where the same value would repeat millions of
 * times.
 *
 * @param profile    id of the embedding profile that built the index
 * @param model      model identifier, to be passed to the embedding service verbatim
 * @param dimensions vector length
 * @param similarity how the vectors are compared
 * @param normalized whether the stored vectors are scaled to unit length - the query vector has to
 *                   be treated the same way
 */
public record RagIndexMetadata(
        String profile,
        String model,
        int dimensions,
        String similarity,
        boolean normalized) {

    static final String KEY_PROFILE = "profile";
    static final String KEY_MODEL = "model";
    static final String KEY_DIMENSIONS = "dimensions";
    static final String KEY_SIMILARITY = "similarity";
    static final String KEY_NORMALIZED = "normalized";

    public Map<String, JsonData> toMeta() {
        Map<String, JsonData> meta = new LinkedHashMap<>();
        meta.put(KEY_PROFILE, JsonData.of(profile));
        meta.put(KEY_MODEL, JsonData.of(model));
        meta.put(KEY_DIMENSIONS, JsonData.of(dimensions));
        meta.put(KEY_SIMILARITY, JsonData.of(similarity));
        meta.put(KEY_NORMALIZED, JsonData.of(normalized));
        return meta;
    }

    /** Reads back what {@link #toMeta()} wrote; null if the index predates this or carries no meta. */
    public static RagIndexMetadata fromMeta(Map<String, JsonData> meta) {
        if (meta == null || !meta.containsKey(KEY_MODEL) || !meta.containsKey(KEY_DIMENSIONS)) {
            return null;
        }
        return new RagIndexMetadata(
                string(meta, KEY_PROFILE),
                string(meta, KEY_MODEL),
                meta.get(KEY_DIMENSIONS).to(Integer.class),
                string(meta, KEY_SIMILARITY),
                meta.containsKey(KEY_NORMALIZED) && Boolean.TRUE.equals(meta.get(KEY_NORMALIZED).to(Boolean.class)));
    }

    private static String string(Map<String, JsonData> meta, String key) {
        return meta.containsKey(key) ? meta.get(key).to(String.class) : null;
    }

    /**
     * Whether an index built under {@code this} can serve vectors produced under {@code other}.
     * Only the things that make two vectors comparable count - the profile id may be renamed.
     */
    public boolean isCompatibleWith(RagIndexMetadata other) {
        return other != null
                && dimensions == other.dimensions
                && normalized == other.normalized
                && java.util.Objects.equals(model, other.model)
                && java.util.Objects.equals(similarity, other.similarity);
    }
}
