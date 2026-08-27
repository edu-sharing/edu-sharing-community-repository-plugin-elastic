package org.edu_sharing.elasticsearch.rag.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fingerprint decides whether a node gets re-embedded, which makes both of its failure modes
 * expensive in opposite directions: too sensitive and every ACL change re-embeds the whole corpus,
 * too coarse and vectors go stale without anyone noticing.
 * <p>
 * The staleness case is the one worth guarding hardest. A renamed title changes the context header
 * that every chunk of that node is embedded with, so it has to change the fingerprint even though
 * not one character of the full text moved.
 */
class ContentFingerprintTest {

    private static ChunkSource source(String text, String title, String description,
                                      List<String> keywords, List<String> subject) {
        return new ChunkSource("uuid-1", "application/pdf", text, title, description,
                keywords, subject, List.of("Sekundarstufe 1"), List.of("Arbeitsblatt"));
    }

    private static ChunkSource standard() {
        return source("Der Bruch wird gekuerzt.", "Bruchrechnen", "Ein Arbeitsblatt.",
                List.of("Bruch"), List.of("Mathematik"));
    }

    @Test
    void isStableAcrossCalls() {
        assertThat(ContentFingerprint.of(standard())).isEqualTo(ContentFingerprint.of(standard()));
    }

    @Test
    void changesWhenTheFullTextChanges() {
        ChunkSource changed = source("Der Bruch wird erweitert.", "Bruchrechnen", "Ein Arbeitsblatt.",
                List.of("Bruch"), List.of("Mathematik"));

        assertThat(ContentFingerprint.of(changed)).isNotEqualTo(ContentFingerprint.of(standard()));
    }

    @Test
    void changesWhenTheTitleChanges() {
        // the title goes into the context header of every chunk, so the vectors are affected
        ChunkSource renamed = source("Der Bruch wird gekuerzt.", "Brueche kuerzen", "Ein Arbeitsblatt.",
                List.of("Bruch"), List.of("Mathematik"));

        assertThat(ContentFingerprint.of(renamed)).isNotEqualTo(ContentFingerprint.of(standard()));
    }

    @Test
    void changesWhenAFacetThatFeedsTheHeaderChanges() {
        ChunkSource reclassified = source("Der Bruch wird gekuerzt.", "Bruchrechnen", "Ein Arbeitsblatt.",
                List.of("Bruch"), List.of("Physik"));

        assertThat(ContentFingerprint.of(reclassified)).isNotEqualTo(ContentFingerprint.of(standard()));
    }

    @Test
    void changesWhenDescriptionOrKeywordsChange() {
        // both feed the metadata chunk, which is the only chunk some nodes ever get
        assertThat(ContentFingerprint.of(source("t", "Titel", "Andere Beschreibung", List.of("a"), List.of("M"))))
                .isNotEqualTo(ContentFingerprint.of(source("t", "Titel", "Beschreibung", List.of("a"), List.of("M"))));
        assertThat(ContentFingerprint.of(source("t", "Titel", "Beschreibung", List.of("b"), List.of("M"))))
                .isNotEqualTo(ContentFingerprint.of(source("t", "Titel", "Beschreibung", List.of("a"), List.of("M"))));
    }

    @Test
    void survivesChangesThatAffectNeitherChunksNorVectors() {
        // an ACL change carries none of these fields - and absorbing those writes is the entire
        // point of having a fingerprint
        ChunkSource other = new ChunkSource("uuid-2", "text/html", "Der Bruch wird gekuerzt.",
                "Bruchrechnen", "Ein Arbeitsblatt.", List.of("Bruch"), List.of("Mathematik"),
                List.of("Sekundarstufe 1"), List.of("Arbeitsblatt"));

        assertThat(ContentFingerprint.of(other)).isEqualTo(ContentFingerprint.of(standard()));
    }

    @Test
    void ignoresWhitespaceTheNormalizerCollapses() {
        // hashing the normalized text, not the raw one, so a re-extraction that only differs in
        // padding or line endings does not trigger a pointless round of re-embedding
        ChunkSource padded = source("Der   Bruch wird gekuerzt.   ", "Bruchrechnen", "Ein Arbeitsblatt.",
                List.of("Bruch"), List.of("Mathematik"));
        ChunkSource crlf = source("Der Bruch wird gekuerzt.\r\n\r\n", "Bruchrechnen", "Ein Arbeitsblatt.",
                List.of("Bruch"), List.of("Mathematik"));

        assertThat(ContentFingerprint.of(padded)).isEqualTo(ContentFingerprint.of(standard()));
        assertThat(ContentFingerprint.of(crlf)).isEqualTo(ContentFingerprint.of(standard()));
    }

    @Test
    void ignoresWhereTheExtractorHappenedToWrapALine() {
        // reversed from an earlier expectation: mid-sentence wraps are now joined, so re-extracting
        // the same document with a different page width no longer looks like a content change
        ChunkSource rewrapped = source("Der Bruch\nwird gekuerzt.", "Bruchrechnen", "Ein Arbeitsblatt.",
                List.of("Bruch"), List.of("Mathematik"));

        assertThat(ContentFingerprint.of(rewrapped)).isEqualTo(ContentFingerprint.of(standard()));
    }

    @Test
    void doesNotConfuseFieldBoundaries() {
        // without length prefixing, ("ab", "c") and ("a", "bc") would hash identically
        assertThat(ContentFingerprint.of(source("x", "ab", "c", List.of(), List.of())))
                .isNotEqualTo(ContentFingerprint.of(source("x", "a", "bc", List.of(), List.of())));
    }

    @Test
    void distinguishesAnEmptyListFromAnEmptyValue() {
        assertThat(ContentFingerprint.of(source("x", "t", "d", List.of(), List.of())))
                .isNotEqualTo(ContentFingerprint.of(source("x", "t", "d", List.of(""), List.of())));
    }

    @Test
    void handlesANodeWithoutFullText() {
        ChunkSource linkOnly = ChunkSource.metadataOnly("uuid-3", "Erklaervideo", "Ein Video.");

        assertThat(ContentFingerprint.of(linkOnly)).isNotBlank().hasSize(64);
    }
}
