package org.edu_sharing.elasticsearch.rag.chunking;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Subtitles are the one source where a retrieval hit is only useful with a playback position
 * attached, so the time range has to survive chunking. Content sniffing matters as much as the
 * format parsing: transcripts routinely arrive as {@code text/plain}.
 */
class TranscriptChunkerTest {

    private final TranscriptChunker chunker = new TranscriptChunker();
    private final ChunkPacker packer = new ChunkPacker();

    /** WebVTT with one cue every 5 seconds. */
    private static String webvtt(int cues) {
        StringBuilder vtt = new StringBuilder("WEBVTT\n\n");
        for (int i = 0; i < cues; i++) {
            vtt.append(stamp(i * 5)).append(" --> ").append(stamp(i * 5 + 5)).append('\n')
                    .append("Sprecher erklaert Schritt ").append(i + 1).append(" der Bruchrechnung.\n\n");
        }
        return vtt.toString();
    }

    private static String stamp(int seconds) {
        return String.format("00:%02d:%02d.000", seconds / 60, seconds % 60);
    }

    @Test
    void recognisesWebVttByMimetype() {
        assertThat(chunker.supports("text/vtt", "")).isTrue();
    }

    @Test
    void recognisesATranscriptDeliveredAsPlainText() {
        assertThat(chunker.supports("text/plain", webvtt(3))).isTrue();
    }

    @Test
    void recognisesSubRipTimingWithACommaSeparator() {
        String srt = "1\n00:00:01,000 --> 00:00:04,000\nErster Untertitel.\n\n";

        assertThat(chunker.supports("text/plain", srt)).isTrue();
    }

    @Test
    void doesNotClaimOrdinaryProse() {
        assertThat(chunker.supports("application/pdf", "Ein Text ohne jede Zeitangabe.")).isFalse();
    }

    @Test
    void keepsTimestampsOutOfTheIndexedText() {
        String vtt = TextNormalizer.normalize(webvtt(4));

        List<Chunk> chunks = packer.pack(vtt, chunker.segment(vtt, ChunkingOptions.DEFAULTS),
                ChunkingOptions.DEFAULTS);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.text()).doesNotContain("-->").doesNotContain("WEBVTT"));
    }

    @Test
    void dropsSubRipSequenceNumbers() {
        String srt = TextNormalizer.normalize(
                "1\n00:00:01,000 --> 00:00:04,000\nErster Untertitel.\n\n"
                        + "2\n00:00:04,000 --> 00:00:08,000\nZweiter Untertitel.\n\n");

        List<Chunk> chunks = packer.pack(srt, chunker.segment(srt, ChunkingOptions.DEFAULTS),
                ChunkingOptions.DEFAULTS);

        assertThat(chunks.get(0).text()).isEqualTo("Erster Untertitel. Zweiter Untertitel.");
    }

    @Test
    void carriesTheTimeRangeOfItsCues() {
        String vtt = TextNormalizer.normalize(webvtt(6));

        List<Chunk> chunks = packer.pack(vtt, chunker.segment(vtt, ChunkingOptions.DEFAULTS),
                ChunkingOptions.DEFAULTS);

        Chunk first = chunks.get(0);
        assertThat(first.timeStart()).isEqualTo(0.0d);
        assertThat(first.timeEnd()).isEqualTo(30.0d);
    }

    @Test
    void closesAWindowBeforeItGrowsPastTheCeiling() {
        // 40 cues x 5s = 200s of speech, well past the 90s ceiling
        String vtt = TextNormalizer.normalize(webvtt(40));

        List<Chunk> chunks = packer.pack(vtt, chunker.segment(vtt, ChunkingOptions.DEFAULTS),
                ChunkingOptions.DEFAULTS);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.timeEnd() - chunk.timeStart()).isLessThanOrEqualTo(90.0d));
    }

    @Test
    void windowsCoverTheWholeTranscriptWithoutGaps() {
        String vtt = TextNormalizer.normalize(webvtt(40));

        List<Chunk> chunks = packer.pack(vtt, chunker.segment(vtt, ChunkingOptions.DEFAULTS),
                ChunkingOptions.DEFAULTS);

        assertThat(chunks.get(0).timeStart()).isEqualTo(0.0d);
        assertThat(chunks.get(chunks.size() - 1).timeEnd()).isEqualTo(200.0d);
        assertThat(IntStream.range(1, chunks.size())
                .allMatch(i -> chunks.get(i).timeStart() <= chunks.get(i - 1).timeEnd())).isTrue();
    }
}
