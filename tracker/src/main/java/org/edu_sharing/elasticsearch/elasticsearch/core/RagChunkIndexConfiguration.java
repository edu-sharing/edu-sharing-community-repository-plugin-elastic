package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch._types.mapping.DenseVectorIndexOptions;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorIndexOptionsType;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.util.ObjectBuilder;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Mapping and settings of one chunk index.
 * <p>
 * Parameterised by embedding profile rather than declared as a fixed {@code @Bean}, because
 * dimensions and similarity come from the model: a profile change means a new physical index next to
 * the old one, not an edit to this one. Deliberately kept out of {@code AutoConfigurationTracker} -
 * that class builds the workspace mapping, is already several hundred lines long, and its beans take
 * no arguments.
 * <p>
 * <strong>{@code dynamic: strict}.</strong> An index that carries the semantic search must not be
 * able to acquire fields by accident; an unexpected metadata-set property has to fail loudly at
 * write time rather than quietly widen the mapping. Everything schema-unstable goes into
 * {@code facetsFlat}, following the precedent {@code flattenedData} set in the workspace index.
 */
public final class RagChunkIndexConfiguration {

    /** HNSW graph connectivity. 16 is the Elasticsearch default and a sane starting point. */
    private static final int HNSW_M = 16;

    /** Candidate list size while the graph is built. Higher means slower writes, better recall. */
    private static final int HNSW_EF_CONSTRUCTION = 100;

    private RagChunkIndexConfiguration() {
    }

    /**
     * @param index      physical index name, {@code rag_chunks_<version>_<profileId>}
     * @param dimensions vector length of the profile's model, e.g. 1024 for {@code BAAI/bge-m3}
     * @param similarity how vectors are compared, normally {@code cosine}
     * @param shards     own shard count - a chunk index holds a multiple of the workspace index's
     *                   documents, so the global setting does not fit
     */
    public static IndexConfiguration create(String index, RagIndexMetadata metadata,
                                            int shards, int replicas) {
        if (metadata.dimensions() <= 0) {
            throw new IllegalArgumentException("dimensions must be > 0, was " + metadata.dimensions());
        }
        return new IndexConfiguration(req -> req
                .index(index)
                .settings(s -> s.index(i -> i
                        .numberOfShards(Integer.toString(shards))
                        .numberOfReplicas(Integer.toString(replicas))))
                .mappings(m -> mappings(m, metadata)));
    }

    static ObjectBuilder<TypeMapping> mappings(TypeMapping.Builder mapping, RagIndexMetadata metadata) {
        int dimensions = metadata.dimensions();
        String similarity = metadata.similarity();
        return mapping
                .dynamic(DynamicMapping.Strict)

                // What built these vectors, so the query side can embed a question the same way
                // without a second copy of the configuration - see RagIndexMetadata.
                .meta(metadata.toMeta())

                // --- identity and bookkeeping -------------------------------------------------
                .properties("nodeId", p -> p.keyword(k -> k))
                .properties("dbid", p -> p.long_(l -> l))
                .properties("aclId", p -> p.long_(l -> l))
                .properties("ordinal", p -> p.short_(sh -> sh))
                .properties("chunkCount", p -> p.short_(sh -> sh))
                .properties("kind", p -> p.keyword(k -> k))

                // Two skip criteria. contentHash covers the full text and the metadata that feeds
                // the context header (see ContentFingerprint) and decides whether the vectors are
                // still valid. metaHash covers the filterable fields, which can change on their own -
                // a corrected licence has to reach the index without paying for a re-embedding.
                .properties("contentHash", p -> p.keyword(k -> k))
                .properties("metaHash", p -> p.keyword(k -> k))

                // --- the two search branches --------------------------------------------------
                // German analysis for the lexical branch: this index is only ever queried on its
                // own, so it does not have to match the workspace index's analyzer. Per-language
                // subfields are a later refinement - facets.language already carries what they
                // would need.
                .properties("text", p -> p.text(t -> t.analyzer("german")))
                .properties("embedding", p -> p.denseVector(v -> v
                        .dims(dimensions)
                        .index(true)
                        .similarity(resolveSimilarity(similarity))
                        // int8 quantisation costs almost no recall at roughly a quarter of the size.
                        // Beyond ~20M chunks BbqHnsw is the next step - far smaller again, but it
                        // trades accuracy and wants a rescore window, so measure before switching.
                        .indexOptions(DenseVectorIndexOptions.of(io -> io
                                .type(DenseVectorIndexOptionsType.Int8Hnsw)
                                .m(HNSW_M)
                                .efConstruction(HNSW_EF_CONSTRUCTION)))))

                // --- access control -----------------------------------------------------------
                // The inputs of edu-sharing's read rule, not its verdict. SearchServiceElastic
                // combines them the same way it does for the workspace index - see RagChunkAccess
                // for why storing a single union was wrong. All flat, so the kNN pre-filter stays
                // cheap: a nested query inside the HNSW traversal is not.
                .properties("readers", p -> p.keyword(k -> k))
                .properties("collectionReaders", p -> p.keyword(k -> k))
                .properties("proposalCoordinators", p -> p.keyword(k -> k))
                .properties("collectionOwners", p -> p.keyword(k -> k))
                .properties("restrictedAccess", p -> p.boolean_(b -> b))
                .properties("restrictedReadAll", p -> p.boolean_(b -> b))
                .properties("owner", p -> p.keyword(k -> k))

                // --- structural filters -------------------------------------------------------
                .properties("type", p -> p.keyword(k -> k))
                .properties("aspects", p -> p.keyword(k -> k))
                .properties("fullpath", p -> p.keyword(k -> k))
                .properties("fullpaths", p -> p.keyword(k -> k))
                // UUIDs of the collections holding a copy, so a search can be scoped to one
                .properties("collections", p -> p.keyword(k -> k))

                // --- didactic facets ----------------------------------------------------------
                .properties("facets", p -> p.object(o -> o
                        .properties("subject", f -> f.keyword(k -> k))
                        .properties("educationalContext", f -> f.keyword(k -> k))
                        .properties("learningResourceType", f -> f.keyword(k -> k))
                        .properties("license", f -> f.keyword(k -> k))
                        .properties("language", f -> f.keyword(k -> k))
                        .properties("source", f -> f.keyword(k -> k))
                        .properties("mimetype", f -> f.keyword(k -> k))
                        .properties("createdAt", f -> f.date(d -> d))
                        .properties("modifiedAt", f -> f.date(d -> d))))

                // Everything else the metadata set defines. One mapping field, arbitrary JSON, no
                // risk of a field explosion - the same reason flattenedData exists in workspace.
                .properties("facetsFlat", p -> p.flattened(f -> f))

                // --- citation, never searched -------------------------------------------------
                .properties("title", p -> p.keyword(k -> k.index(false)))
                .properties("heading", p -> p.keyword(k -> k.index(false)))
                .properties("charStart", p -> p.integer(i -> i.index(false)))
                .properties("charEnd", p -> p.integer(i -> i.index(false)))
                .properties("page", p -> p.integer(i -> i.index(false)))
                .properties("timeStart", p -> p.float_(f -> f.index(false)))
                .properties("timeEnd", p -> p.float_(f -> f.index(false)));
    }

    /**
     * Resolves the configured similarity by its wire name ({@code cosine}, {@code dot_product}, …)
     * rather than by the Java constant, so the value in the properties file reads the way the
     * Elasticsearch documentation writes it. A typo fails at startup with the valid values listed,
     * instead of silently creating an index whose vectors are compared the wrong way.
     */
    static DenseVectorSimilarity resolveSimilarity(String similarity) {
        if (similarity == null || similarity.isBlank()) {
            return DenseVectorSimilarity.Cosine;
        }
        for (DenseVectorSimilarity candidate : DenseVectorSimilarity.values()) {
            if (candidate.jsonValue().equalsIgnoreCase(similarity.trim())) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown similarity '" + similarity + "', expected one of "
                + Arrays.stream(DenseVectorSimilarity.values())
                .map(DenseVectorSimilarity::jsonValue)
                .collect(Collectors.joining(", ")));
    }
}
