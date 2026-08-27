package org.edu_sharing.elasticsearch.rag.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour of the whole chunking step. The properties asserted here are the ones the rest of the
 * RAG pipeline depends on: no chunk exceeds the model's budget, consecutive chunks overlap, every
 * node produces a metadata chunk, truncation is reported rather than silent - and, above all,
 * chunking is deterministic, because the tracker's re-embedding skip is only sound if it is.
 */
class ChunkingServiceTest {

    private final ChunkingService service = new ChunkingService();

    private static final ChunkingOptions OPTIONS = ChunkingOptions.DEFAULTS;

    /** ~14 tokens per sentence, each individually identifiable. */
    private static String prose(int sentences) {
        return prose(1, sentences);
    }

    private static String prose(int firstSentence, int sentences) {
        return IntStream.range(0, sentences)
                .mapToObj(i -> "Abschnitt " + (firstSentence + i)
                        + " erklaert das Kuerzen von Bruechen im Unterricht.")
                .reduce((a, b) -> a + " " + b)
                .orElse("");
    }

    /** Longest suffix of {@code previous} that is also a prefix of {@code next}. */
    private static String overlapBetween(String previous, String next) {
        for (int length = Math.min(previous.length(), next.length()); length > 0; length--) {
            if (previous.endsWith(next.substring(0, length))) {
                return next.substring(0, length);
            }
        }
        return "";
    }

    private static ChunkSource source(String text) {
        return new ChunkSource("uuid-1", "application/pdf", text,
                "Bruchrechnen", "Ein Arbeitsblatt zum Kuerzen von Bruechen.",
                List.of("Bruch", "Kuerzen"), List.of("Mathematik"),
                List.of("Sekundarstufe 1"), List.of("Arbeitsblatt"));
    }

    private static List<Chunk> contentOf(ChunkingResult result) {
        return result.chunks().stream().filter(c -> c.kind() == ChunkKind.CONTENT).toList();
    }

    @Test
    void producesAMetadataChunkFirst() {
        ChunkingResult result = service.chunk(source(prose(40)), OPTIONS);

        Chunk first = result.chunks().get(0);
        assertThat(first.kind()).isEqualTo(ChunkKind.METADATA);
        assertThat(first.ordinal()).isZero();
        assertThat(first.text())
                .contains("Bruchrechnen")
                .contains("Schlagworte: Bruch, Kuerzen")
                .contains("Fach: Mathematik")
                .contains("Bildungsstufe: Sekundarstufe 1")
                .contains("Materialart: Arbeitsblatt");
    }

    @Test
    void indexesNodesThatHaveNoFullTextAtAll() {
        // links, images, videos without a transcript - a large part of the WLO stock
        ChunkingResult result = service.chunk(
                ChunkSource.metadataOnly("uuid-2", "Erklaervideo Bruchrechnen", "Ein Video."), OPTIONS);

        assertThat(result.chunks()).hasSize(1);
        assertThat(result.chunks().get(0).kind()).isEqualTo(ChunkKind.METADATA);
    }

    @Test
    void returnsNothingForANodeWithNeitherTextNorMetadata() {
        ChunkingResult result = service.chunk(
                new ChunkSource("uuid-3", null, null, null, null, null, null, null, null), OPTIONS);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void neverExceedsTheTokenCeiling() {
        ChunkingResult result = service.chunk(source(prose(400)), OPTIONS);

        assertThat(contentOf(result)).isNotEmpty().allSatisfy(chunk ->
                assertThat(TokenEstimator.estimate(chunk.text()))
                        .as("chunk %d", chunk.ordinal())
                        .isLessThanOrEqualTo(OPTIONS.maxTokens()));
    }

    @Test
    void assignsContiguousOrdinals() {
        ChunkingResult result = service.chunk(source(prose(200)), OPTIONS);

        assertThat(result.chunks()).extracting(Chunk::ordinal)
                .containsExactlyElementsOf(IntStream.range(0, result.size()).boxed().toList());
    }

    @Test
    void consecutiveChunksOverlapAtASentenceBoundary() {
        List<Chunk> content = contentOf(service.chunk(source(prose(200)), OPTIONS));
        assertThat(content).hasSizeGreaterThan(2);

        for (int i = 1; i < content.size(); i++) {
            String previous = content.get(i - 1).text();
            String current = content.get(i).text();
            String overlap = overlapBetween(previous, current);

            assertThat(overlap)
                    .as("chunk %d must repeat the tail of chunk %d", i, i - 1)
                    .isNotEmpty()
                    .as("the repeated part must be whole sentences, not a fragment")
                    .endsWith(".");
            assertThat(current).startsWith(overlap);
        }
    }

    @Test
    void doesNotLeaveAStrandedFragmentAtTheEnd() {
        // 41 sentences lands just past a chunk boundary, which is exactly how orphans appear
        List<Chunk> content = contentOf(service.chunk(source(prose(41)), OPTIONS));

        assertThat(content).allSatisfy(chunk ->
                assertThat(TokenEstimator.estimate(chunk.text()))
                        .isGreaterThanOrEqualTo(OPTIONS.minTokens()));
    }

    @Test
    void embeddingTextCarriesTheContextHeaderButStoredTextDoesNot() {
        Chunk chunk = contentOf(service.chunk(source(prose(60)), OPTIONS)).get(0);

        assertThat(chunk.text()).doesNotContain("Mathematik");
        assertThat(chunk.embeddingText())
                .startsWith("Bruchrechnen — Mathematik — Sekundarstufe 1")
                .endsWith(chunk.text());
    }

    @Test
    void reportsTruncationInsteadOfDiscardingSilently() {
        ChunkingOptions tight = new ChunkingOptions(450, 800, 64, 80, 3);

        ChunkingResult result = service.chunk(source(prose(400)), tight);

        assertThat(result.size()).isEqualTo(3);
        assertThat(result.truncated()).isTrue();
        assertThat(result.droppedChunks()).isPositive();
    }

    @Test
    void keepsTheHeadOfTheDocumentWhenTruncating() {
        ChunkingOptions tight = new ChunkingOptions(450, 800, 64, 80, 2);

        ChunkingResult result = service.chunk(source(prose(400)), tight);

        assertThat(contentOf(result).get(0).text()).contains("Abschnitt 1 ");
    }

    @Test
    void isDeterministic() {
        ChunkSource source = source(prose(300));

        assertThat(service.chunk(source, OPTIONS)).isEqualTo(service.chunk(source, OPTIONS));
    }

    @Test
    void charOffsetsPointBackIntoTheNormalizedText() {
        String text = prose(120);
        String normalized = TextNormalizer.normalize(text);

        for (Chunk chunk : contentOf(service.chunk(source(text), OPTIONS))) {
            assertThat(chunk.charEnd()).isGreaterThan(chunk.charStart());
            assertThat(chunk.charEnd()).isLessThanOrEqualTo(normalized.length());
            assertThat(normalized.substring(chunk.charStart(), chunk.charEnd()))
                    .as("chunk %d", chunk.ordinal())
                    .contains(chunk.text().substring(0, Math.min(30, chunk.text().length())));
        }
    }

    @Test
    void splitsAtPageBreaksAndRecordsThePageNumber() {
        String paged = prose(1, 20) + "\f" + prose(21, 20) + "\f" + prose(41, 20);

        List<Chunk> content = contentOf(service.chunk(source(paged), OPTIONS));

        assertThat(content).extracting(Chunk::page).containsExactly(1, 2, 3);
    }

    @Test
    void leavesThePageNumberEmptyWhenTheExtractorGaveNoPageBreaks() {
        List<Chunk> content = contentOf(service.chunk(source(prose(60)), OPTIONS));

        assertThat(content).allSatisfy(chunk -> assertThat(chunk.page()).isNull());
    }

    @Test
    void attachesTheHeadingAChunkSitsUnder() {
        String withHeadings = "1 Einfuehrung\n\n" + prose(30) + "\n\n2 Vertiefung\n\n" + prose(30);

        List<Chunk> content = contentOf(service.chunk(source(withHeadings), OPTIONS));

        assertThat(content).extracting(Chunk::heading).contains("1 Einfuehrung", "2 Vertiefung");
    }
}
