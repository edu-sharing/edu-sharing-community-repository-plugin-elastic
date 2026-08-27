package org.edu_sharing.elasticsearch.rag.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The splitter is tuned to over-merge rather than over-split: a missed boundary only makes a chunk
 * slightly longer, whereas a wrong one puts half a sentence into the index. These tests pin that
 * asymmetry down, because it is the kind of behaviour a later "improvement" quietly reverses.
 */
class SentenceSplitterTest {

    private static List<String> sentences(String text) {
        return SentenceSplitter.split(text, 0).stream().map(s -> s.of(text)).toList();
    }

    @Test
    void splitsOnPlainSentenceBoundaries() {
        assertThat(sentences("Der Bruch wird gekürzt. Danach folgt die Probe. Fertig!"))
                .containsExactly("Der Bruch wird gekürzt.", "Danach folgt die Probe.", "Fertig!");
    }

    @Test
    void keepsGermanOrdinalsTogether() {
        assertThat(sentences("Das Thema wird in der 6. Klasse behandelt."))
                .containsExactly("Das Thema wird in der 6. Klasse behandelt.");
    }

    @Test
    void keepsDatesAndDecimalsTogether() {
        assertThat(sentences("Am 1. Januar beginnt das Halbjahr.")).hasSize(1);
        assertThat(sentences("Der Wert beträgt 3.14 Einheiten.")).hasSize(1);
    }

    @Test
    void keepsAbbreviationsTogether() {
        assertThat(sentences("Brüche treten z.B. beim Backen auf. Das ist bekannt."))
                .containsExactly("Brüche treten z.B. beim Backen auf.", "Das ist bekannt.");
        assertThat(sentences("Vgl. dazu Kap. 3 der Handreichung.")).hasSize(1);
    }

    @Test
    void keepsInitialsTogether() {
        assertThat(sentences("Das Verfahren geht auf A. Müller zurück.")).hasSize(1);
    }

    @Test
    void splitsOnQuestionAndExclamation() {
        assertThat(sentences("Wie kürzt man Brüche? Man teilt durch den ggT! Das war es."))
                .hasSize(3);
    }

    @Test
    void spansPointBackIntoTheSourceText() {
        String text = "Erster Satz. Zweiter Satz.";
        List<SentenceSplitter.Span> spans = SentenceSplitter.split(text, 0);

        assertThat(spans).hasSize(2);
        assertThat(text.substring(spans.get(1).start(), spans.get(1).end())).isEqualTo("Zweiter Satz.");
    }

    @Test
    void spansAreOffsetByTheGivenBase() {
        List<SentenceSplitter.Span> spans = SentenceSplitter.split("Ein Satz.", 100);

        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).start()).isEqualTo(100);
        assertThat(spans.get(0).end()).isEqualTo(109);
    }

    @Test
    void ignoresBlankInput() {
        assertThat(SentenceSplitter.split("   \n  ", 0)).isEmpty();
        assertThat(SentenceSplitter.split(null, 0)).isEmpty();
    }
}
