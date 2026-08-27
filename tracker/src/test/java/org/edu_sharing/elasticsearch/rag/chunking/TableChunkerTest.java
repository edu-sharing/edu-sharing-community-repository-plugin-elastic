package org.edu_sharing.elasticsearch.rag.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A block of rows without its column names means little to a reader and less to an embedding model,
 * so the header is repeated per block. Blocks are hard boundaries: unlike prose, repeating rows
 * across a boundary would add no context, so there is deliberately no overlap here.
 */
class TableChunkerTest {

    private final TableChunker chunker = new TableChunker();
    private final ChunkPacker packer = new ChunkPacker();

    private static String csv(int rows) {
        return "Titel;Fach;Klassenstufe\n" + IntStream.rangeClosed(1, rows)
                .mapToObj(i -> "Arbeitsblatt " + i + ";Mathematik;Klasse " + (5 + i % 5))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private List<Chunk> chunk(String text, ChunkingOptions options) {
        String normalized = TextNormalizer.normalize(text);
        return packer.pack(normalized, chunker.segment(normalized, options), options);
    }

    @Test
    void claimsCsvByMimetype() {
        assertThat(chunker.supports("text/csv", "")).isTrue();
        assertThat(chunker.supports("text/tab-separated-values", "")).isTrue();
    }

    @Test
    void leavesOtherFormatsAlone() {
        assertThat(chunker.supports("application/pdf", "")).isFalse();
        assertThat(chunker.supports(null, "")).isFalse();
    }

    @Test
    void repeatsTheHeaderInEveryBlock() {
        List<Chunk> chunks = chunk(csv(200), ChunkingOptions.DEFAULTS);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.text()).startsWith("Titel;Fach;Klassenstufe"));
    }

    @Test
    void doesNotCarryOverlapBetweenBlocks() {
        List<Chunk> chunks = chunk(csv(200), ChunkingOptions.DEFAULTS);

        // every data row appears exactly once across all chunks
        String all = String.join("\n", chunks.stream().map(Chunk::text).toList());
        assertThat(all.split("Arbeitsblatt 7;", -1)).hasSize(2);
    }

    @Test
    void indexesAHeaderOnlyFile() {
        List<Chunk> chunks = chunk("Titel;Fach;Klassenstufe\n", ChunkingOptions.DEFAULTS);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("Titel;Fach;Klassenstufe");
    }

    @Test
    void staysWithinTheTokenCeiling() {
        List<Chunk> chunks = chunk(csv(500), ChunkingOptions.DEFAULTS);

        assertThat(chunks).allSatisfy(c ->
                assertThat(TokenEstimator.estimate(c.text()))
                        .isLessThanOrEqualTo(ChunkingOptions.DEFAULTS.maxTokens()));
    }
}
