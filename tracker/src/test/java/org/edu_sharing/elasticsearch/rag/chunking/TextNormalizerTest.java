package org.edu_sharing.elasticsearch.rag.chunking;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Normalization sits in front of everything else, so its output is what {@code contentHash} is
 * computed over and what every chunk offset refers to. It has to be stable and it must not destroy
 * the one structural marker that survives text extraction: the form feed between pages.
 */
class TextNormalizerTest {

    @Test
    void joinsHyphenationAcrossLineBreaks() {
        assertThat(TextNormalizer.normalize("Die Bruch-\nrechnung ist Thema."))
                .isEqualTo("Die Bruchrechnung ist Thema.");
    }

    @Test
    void keepsRealCompoundsIntact() {
        // uppercase after the hyphen means it is a compound, not a typesetting artefact
        assertThat(TextNormalizer.normalize("Der Nord-\nWesten ist gemeint."))
                .isEqualTo("Der Nord-\nWesten ist gemeint.");
    }

    @Test
    void preservesPageBreaks() {
        assertThat(TextNormalizer.normalize("Seite eins\fSeite zwei")).contains("\f");
    }

    @Test
    void collapsesWhitespaceAndBlankLines() {
        assertThat(TextNormalizer.normalize("a   b\t\tc\n\n\n\n\nd"))
                .isEqualTo("a b c\n\nd");
    }

    @Test
    void normalizesLineEndings() {
        // sentences rather than bare letters, so the de-wrap rule does not join them and this
        // really only asserts what its name says
        assertThat(TextNormalizer.normalize("Ende.\r\nNeuer Satz.\rNoch einer."))
                .isEqualTo("Ende.\nNeuer Satz.\nNoch einer.");
    }

    @Test
    void stripsRunningFooterEvenWhenThePageNumberChanges() {
        String paged = "Kapitel eins\nInhalt A\nSeite 1 von 4\f"
                + "Kapitel zwei\nInhalt B\nSeite 2 von 4\f"
                + "Kapitel drei\nInhalt C\nSeite 3 von 4\f"
                + "Kapitel vier\nInhalt D\nSeite 4 von 4";

        String normalized = TextNormalizer.normalize(paged);

        assertThat(normalized).doesNotContain("Seite 1 von 4").doesNotContain("Seite 4 von 4");
        assertThat(normalized).contains("Inhalt A").contains("Inhalt D");
    }

    @Test
    void leavesRepeatedLinesAloneWhenThereIsTooLittleEvidence() {
        // two pages is not enough to conclude anything about repetition
        String paged = "Titel\nInhalt A\ffTitel\nInhalt B".replace("\ff", "\f");

        assertThat(TextNormalizer.normalize(paged)).contains("Titel");
    }

    @Test
    void isDeterministic() {
        String input = "Ein  Text\r\nmit Um-\nbrüchen\n\n\n und Seite 1 von 2\fmehr Text\nSeite 2 von 2";

        assertThat(TextNormalizer.normalize(input)).isEqualTo(TextNormalizer.normalize(input));
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(TextNormalizer.normalize(null)).isEmpty();
        assertThat(TextNormalizer.normalize("")).isEmpty();
    }

    @Test
    void neverErasesAWholePage() {
        // every line of a one-line page is simultaneously its head and its foot; without a guard
        // the repetition check deletes the page's only line and the document comes out empty
        String paged = "Seite eins hat nur eine Zeile.\fSeite zwei hat nur eine Zeile.\fSeite drei hat nur eine Zeile.";

        String normalized = TextNormalizer.normalize(paged);

        assertThat(normalized).contains("Seite eins").contains("Seite zwei").contains("Seite drei");
    }

    @Test
    void countsARepeatedLineOncePerPage() {
        // three pages with a genuine running head, each with enough body to survive
        String paged = "Handreichung\nEinleitung A\nMehr Text A\nSchluss A\f"
                + "Handreichung\nEinleitung B\nMehr Text B\nSchluss B\f"
                + "Handreichung\nEinleitung C\nMehr Text C\nSchluss C";

        String normalized = TextNormalizer.normalize(paged);

        assertThat(normalized).doesNotContain("Handreichung");
        assertThat(normalized).contains("Mehr Text A").contains("Mehr Text C");
    }

    @Test
    void joinsLinesTheExtractorWrappedMidSentence() {
        // measured on real output: 2366 of 5561 lines were wrapped this way, and every one of them
        // ended up inside the text handed to the embedding model
        assertThat(TextNormalizer.normalize("Doch zum einen kann sich die Schulleiterin gut\nvorstellen, dass es die PLGen gibt."))
                .isEqualTo("Doch zum einen kann sich die Schulleiterin gut vorstellen, dass es die PLGen gibt.");
    }

    @Test
    void leavesAHeadingOnItsOwnLine() {
        // a heading is followed by something that starts uppercase, so the join never applies -
        // which is what keeps heading detection working
        assertThat(TextNormalizer.normalize("2 Kuerzen von Bruechen\nDer Bruch wird gekuerzt."))
                .isEqualTo("2 Kuerzen von Bruechen\nDer Bruch wird gekuerzt.");
    }

    @Test
    void doesNotJoinAcrossAParagraphBoundary() {
        assertThat(TextNormalizer.normalize("Ende des Absatzes\n\nund weiter geht es."))
                .isEqualTo("Ende des Absatzes\n\nund weiter geht es.");
    }

    @Test
    void doesNotJoinAfterASentenceEnd() {
        assertThat(TextNormalizer.normalize("Erster Satz.\nzweiter beginnt klein."))
                .isEqualTo("Erster Satz.\nzweiter beginnt klein.");
    }

    @Test
    void joinsAWrappedLineEvenWhenGermanCapitalisesTheNextWord() {
        // the case a lowercase-only rule misses: every German noun is capitalised, so this break
        // survived and left the sentence split in the indexed text
        String wrapped = "Es ist so konzipiert, dass es auch fuer den Mathematikunterricht in anderen\n"
                + "Bundeslaendern geeignet ist.";

        assertThat(TextNormalizer.normalize(wrapped))
                .isEqualTo("Es ist so konzipiert, dass es auch fuer den Mathematikunterricht in anderen "
                        + "Bundeslaendern geeignet ist.");
        // and exactly one space at the seam, not the two the join would otherwise leave
        assertThat(TextNormalizer.normalize(wrapped)).doesNotContain("  ");
    }

    @Test
    void keepsAShortHeadingOnItsOwnLine() {
        // short enough not to be a wrap, and the next line starts uppercase - so it stays a heading
        assertThat(TextNormalizer.normalize("2 Kuerzen von Bruechen\nDer Bruch wird gekuerzt."))
                .isEqualTo("2 Kuerzen von Bruechen\nDer Bruch wird gekuerzt.");
    }

    @Test
    void dropsGlyphsFromSymbolFonts() {
        // private use area: Wingdings bullets and the like, meaningless to a reader and to a model
        assertThat(TextNormalizer.normalize("Ein Punkt \uF0A0 und weiter."))
                .isEqualTo("Ein Punkt und weiter.");
    }
}
