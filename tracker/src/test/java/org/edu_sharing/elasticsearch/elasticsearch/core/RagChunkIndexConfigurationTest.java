package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch._types.mapping.DenseVectorIndexOptionsType;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorProperty;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asserts the properties of the chunk mapping that other parts of the design silently depend on.
 * <p>
 * These are checked as typed builder output rather than against a golden JSON file on purpose: a
 * mapping fixture produces diffs nobody can review, and it fails on formatting churn as readily as
 * on a real change. What matters here is a short list of specific guarantees - the vector is
 * searchable and quantised, the read authorities are a filterable keyword, and the mapping cannot
 * grow fields by accident.
 */
class RagChunkIndexConfigurationTest {

    private static TypeMapping mapping() {
        return mapping(1024, "cosine");
    }

    private static TypeMapping mapping(int dimensions, String similarity) {
        return RagChunkIndexConfiguration.mappings(new TypeMapping.Builder(),
                new RagIndexMetadata("bge-m3-v1", "BAAI/bge-m3", dimensions, similarity, true)).build();
    }

    private static DenseVectorProperty embedding(TypeMapping mapping) {
        return mapping.properties().get("embedding").denseVector();
    }

    @Test
    void refusesToGrowFieldsByAccident() {
        // an unexpected metadata-set property must fail the write, not widen the mapping
        assertThat(mapping().dynamic()).isEqualTo(DynamicMapping.Strict);
    }

    @Test
    void makesTheVectorSearchable() {
        DenseVectorProperty embedding = embedding(mapping());

        assertThat(embedding.dims()).isEqualTo(1024);
        assertThat(embedding.index()).isTrue();
        assertThat(embedding.similarity()).isEqualTo(DenseVectorSimilarity.Cosine);
    }

    @Test
    void quantisesTheVector() {
        assertThat(embedding(mapping()).indexOptions().type())
                .isEqualTo(DenseVectorIndexOptionsType.Int8Hnsw);
    }

    @Test
    void takesDimensionsFromTheProfile() {
        assertThat(embedding(mapping(768, "cosine")).dims()).isEqualTo(768);
    }

    @Test
    void acceptsSimilarityByItsWireName() {
        assertThat(RagChunkIndexConfiguration.resolveSimilarity("dot_product"))
                .isEqualTo(DenseVectorSimilarity.DotProduct);
        assertThat(RagChunkIndexConfiguration.resolveSimilarity("COSINE"))
                .isEqualTo(DenseVectorSimilarity.Cosine);
    }

    @Test
    void rejectsAnUnknownSimilarityAtStartup() {
        // silently creating an index whose vectors are compared the wrong way is unrecoverable
        // without a full rebuild, so this has to fail loudly and name the valid values
        assertThatThrownBy(() -> RagChunkIndexConfiguration.resolveSimilarity("cosinus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cosinus")
                .hasMessageContaining("dot_product");
    }

    @Test
    void rejectsAnImpossibleDimension() {
        assertThatThrownBy(() -> RagChunkIndexConfiguration.create("rag_chunks_test",
                new RagIndexMetadata("p", "m", 0, "cosine", true), 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordsWhatBuiltTheVectors() {
        // the query side has to embed the question with the same model, and reading it off the index
        // means there is no second copy of the configuration that could drift
        RagIndexMetadata meta = RagIndexMetadata.fromMeta(mapping().meta());

        assertThat(meta).isNotNull();
        assertThat(meta.model()).isEqualTo("BAAI/bge-m3");
        assertThat(meta.dimensions()).isEqualTo(1024);
        assertThat(meta.similarity()).isEqualTo("cosine");
        assertThat(meta.normalized()).isTrue();
        assertThat(meta.profile()).isEqualTo("bge-m3-v1");
    }

    @Test
    void treatsADifferentModelAsIncompatible() {
        RagIndexMetadata built = new RagIndexMetadata("a", "BAAI/bge-m3", 1024, "cosine", true);

        assertThat(built.isCompatibleWith(built)).isTrue();
        // a rename of the profile alone does not make vectors incomparable
        assertThat(built.isCompatibleWith(
                new RagIndexMetadata("anders", "BAAI/bge-m3", 1024, "cosine", true))).isTrue();
        assertThat(built.isCompatibleWith(
                new RagIndexMetadata("a", "gte-multilingual-base", 1024, "cosine", true))).isFalse();
        assertThat(built.isCompatibleWith(
                new RagIndexMetadata("a", "BAAI/bge-m3", 768, "cosine", true))).isFalse();
        assertThat(built.isCompatibleWith(
                new RagIndexMetadata("a", "BAAI/bge-m3", 1024, "dot_product", true))).isFalse();
        assertThat(built.isCompatibleWith(null)).isFalse();
    }

    @Test
    void toleratesAnIndexWrittenBeforeThisWasRecorded() {
        assertThat(RagIndexMetadata.fromMeta(java.util.Map.of())).isNull();
        assertThat(RagIndexMetadata.fromMeta(null)).isNull();
    }

    @Test
    void keepsReadAuthoritiesFilterable() {
        // this field is the pre-filter of every kNN query; as a text field it would silently
        // stop matching authority names exactly
        assertThat(mapping().properties().get("readers").isKeyword()).isTrue();
    }

    @Test
    void keepsAccessFlatRatherThanNestingCollections() {
        // the kNN filter is walked during the HNSW traversal, where a nested query beside a dense
        // vector is both heavy and awkward - so the workspace index's nested collections are not
        // rebuilt here
        assertThat(mapping().properties()).doesNotContainKey("permissions");
        assertThat(mapping().properties().get("collections").isKeyword()).isTrue();
    }

    @Test
    void carriesTheInputsOfTheReadRuleRatherThanItsVerdict() {
        // a single union was too permissive: it ignored ccm:restricted_access and showed proposals
        // to everyone who may read the collection instead of only to its coordinators. The rule
        // stays in SearchServiceElastic; these are the values it combines.
        TypeMapping mapping = mapping();

        assertThat(mapping.properties().get("readers").isKeyword()).isTrue();
        assertThat(mapping.properties().get("collectionReaders").isKeyword()).isTrue();
        assertThat(mapping.properties().get("proposalCoordinators").isKeyword()).isTrue();
        assertThat(mapping.properties().get("collectionOwners").isKeyword()).isTrue();
        assertThat(mapping.properties().get("restrictedAccess").isBoolean()).isTrue();
        assertThat(mapping.properties().get("restrictedReadAll").isBoolean()).isTrue();
    }

    @Test
    void analysesTheLexicalBranchAsGerman() {
        assertThat(mapping().properties().get("text").text().analyzer()).isEqualTo("german");
    }

    @Test
    void mapsTheFixedFacetsAsKeywordsAndDates() {
        Property facets = mapping().properties().get("facets");

        assertThat(facets.isObject()).isTrue();
        assertThat(facets.object().properties().get("license").isKeyword()).isTrue();
        assertThat(facets.object().properties().get("educationalContext").isKeyword()).isTrue();
        assertThat(facets.object().properties().get("modifiedAt").isDate()).isTrue();
    }

    @Test
    void absorbsUnstableMetadataInASingleField() {
        // the flattenedData precedent: arbitrary JSON, one mapping field, no field explosion
        assertThat(mapping().properties().get("facetsFlat").isFlattened()).isTrue();
    }

    @Test
    void doesNotIndexTheCitationFields() {
        TypeMapping mapping = mapping();

        assertThat(mapping.properties().get("title").keyword().index()).isFalse();
        assertThat(mapping.properties().get("page").integer().index()).isFalse();
        assertThat(mapping.properties().get("timeStart").float_().index()).isFalse();
    }

    @Test
    void carriesTheJoinAndSkipKeys() {
        TypeMapping mapping = mapping();

        assertThat(mapping.properties().get("nodeId").isKeyword()).isTrue();
        assertThat(mapping.properties().get("contentHash").isKeyword()).isTrue();
        assertThat(mapping.properties().get("aclId").isLong()).isTrue();
        assertThat(mapping.properties().get("ordinal").isShort()).isTrue();
    }

    @Test
    void namesTheIndexAsGiven() {
        IndexConfiguration configuration = RagChunkIndexConfiguration.create("rag_chunks_11.0_bge-m3-v1",
                new RagIndexMetadata("bge-m3-v1", "BAAI/bge-m3", 1024, "cosine", true), 3, 1);

        assertThat(configuration.getIndex()).isEqualTo("rag_chunks_11.0_bge-m3-v1");
    }
}
