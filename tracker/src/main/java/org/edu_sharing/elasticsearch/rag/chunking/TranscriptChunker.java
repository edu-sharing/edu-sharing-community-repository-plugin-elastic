package org.edu_sharing.elasticsearch.rag.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebVTT and SubRip subtitles, and by extension any media whose text track was extracted.
 * <p>
 * Cuts into time windows rather than by length, because a subtitle file has no paragraphs to cut at
 * and a retrieval hit is only useful if it can point at a playback position. Windows close at a
 * speech pause once they are long enough, and are forced shut at {@link #MAX_WINDOW_SECONDS}, so a
 * continuous monologue still produces bounded chunks.
 * <p>
 * One piece is one subtitle line - no sentence splitting. Subtitle lines are already short and cut
 * at natural speech boundaries, and running the sentence splitter over cue text reassembled across
 * timestamp lines would need offsets mapped back through the join, for no gain.
 */
class TranscriptChunker implements Chunker {

    /** Both dialects: WebVTT uses a dot before the milliseconds, SubRip a comma. */
    private static final Pattern CUE_TIMING = Pattern.compile(
            "(?m)^(?<from>\\d{1,3}:\\d{2}(?::\\d{2})?[.,]\\d{1,3})\\s*-->\\s*"
                    + "(?<to>\\d{1,3}:\\d{2}(?::\\d{2})?[.,]\\d{1,3}).*$");

    /** Window may close here once it is long enough. */
    private static final double MIN_WINDOW_SECONDS = 60d;

    /** Window closes here regardless of pauses. */
    private static final double MAX_WINDOW_SECONDS = 90d;

    /** Silence between two cues that counts as a speech pause. */
    private static final double PAUSE_SECONDS = 1.0d;

    /** How far into the text we look for a cue timing before giving up on the sniff. */
    private static final int SNIFF_LIMIT = 4000;

    @Override
    public boolean supports(String mimetype, String normalizedText) {
        if (mimetype != null) {
            String type = mimetype.toLowerCase();
            if (type.contains("vtt") || type.contains("subrip") || type.endsWith("/srt")) {
                return true;
            }
        }
        // Transcripts frequently arrive as text/plain, so fall back to looking at the content.
        String head = normalizedText.length() > SNIFF_LIMIT
                ? normalizedText.substring(0, SNIFF_LIMIT)
                : normalizedText;
        return CUE_TIMING.matcher(head).find();
    }

    @Override
    public List<Piece> segment(String normalizedText, ChunkingOptions options) {
        List<Cue> cues = parseCues(normalizedText);
        List<Piece> pieces = new ArrayList<>();

        int group = 0;
        double windowStart = Double.NaN;
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            if (Double.isNaN(windowStart)) {
                windowStart = cue.from();
            }
            for (int line = 0; line < cue.lines().size(); line++) {
                pieces.add(new Piece(
                        cue.lines().get(line),
                        group,
                        Piece.JOIN_INLINE,
                        false,
                        null,
                        null,
                        cue.from(),
                        cue.to()));
            }
            if (shouldCloseWindow(cues, i, windowStart)) {
                group++;
                windowStart = Double.NaN;
            }
        }
        return pieces;
    }

    private boolean shouldCloseWindow(List<Cue> cues, int index, double windowStart) {
        Cue cue = cues.get(index);
        double elapsed = cue.to() - windowStart;
        if (elapsed >= MAX_WINDOW_SECONDS) {
            return true;
        }
        if (elapsed < MIN_WINDOW_SECONDS || index + 1 >= cues.size()) {
            return false;
        }
        return cues.get(index + 1).from() - cue.to() >= PAUSE_SECONDS;
    }

    private List<Cue> parseCues(String text) {
        List<Cue> cues = new ArrayList<>();
        Matcher matcher = CUE_TIMING.matcher(text);
        List<int[]> timings = new ArrayList<>();
        List<double[]> times = new ArrayList<>();
        while (matcher.find()) {
            timings.add(new int[]{matcher.start(), matcher.end()});
            times.add(new double[]{seconds(matcher.group("from")), seconds(matcher.group("to"))});
        }
        for (int i = 0; i < timings.size(); i++) {
            int bodyStart = timings.get(i)[1];
            int bodyEnd = i + 1 < timings.size() ? timings.get(i + 1)[0] : text.length();
            List<SentenceSplitter.Span> lines = textLines(text, bodyStart, bodyEnd);
            if (!lines.isEmpty()) {
                cues.add(new Cue(times.get(i)[0], times.get(i)[1], lines));
            }
        }
        return cues;
    }

    /**
     * The non-blank lines between two cue timings, minus the SubRip sequence number that precedes
     * the next timing.
     */
    private List<SentenceSplitter.Span> textLines(String text, int from, int to) {
        List<SentenceSplitter.Span> spans = new ArrayList<>();
        int lineStart = from;
        for (int i = from; i <= to; i++) {
            boolean end = i == to || text.charAt(i) == '\n';
            if (!end) {
                continue;
            }
            int s = lineStart;
            int e = i;
            while (s < e && Character.isWhitespace(text.charAt(s))) {
                s++;
            }
            while (e > s && Character.isWhitespace(text.charAt(e - 1))) {
                e--;
            }
            if (s < e && !isSequenceNumber(text, s, e)) {
                spans.add(new SentenceSplitter.Span(s, e));
            }
            lineStart = i + 1;
        }
        return spans;
    }

    private boolean isSequenceNumber(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** {@code HH:MM:SS.mmm} or {@code MM:SS.mmm}, either dialect of decimal separator. */
    private static double seconds(String stamp) {
        String[] parts = stamp.replace(',', '.').split(":");
        double total = 0d;
        for (String part : parts) {
            total = total * 60d + Double.parseDouble(part);
        }
        return total;
    }

    private record Cue(double from, double to, List<SentenceSplitter.Span> lines) {
    }
}
