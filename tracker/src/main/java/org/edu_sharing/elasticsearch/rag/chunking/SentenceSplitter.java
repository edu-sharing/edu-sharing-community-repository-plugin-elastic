package org.edu_sharing.elasticsearch.rag.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Splits German text into sentences, returning offsets so that each piece keeps its position in the
 * source.
 * <p>
 * This is a rule-based splitter rather than a model: pulling in an NLP dependency to find full stops
 * would be a poor trade for a step whose only job is to give the packer somewhere sensible to cut.
 * <p>
 * The rules lean deliberately towards <em>not</em> splitting. A missed boundary merely produces a
 * slightly longer chunk, which the packer handles; a wrong boundary cuts a sentence in half and puts
 * a fragment into the index, which is the failure that actually degrades retrieval. Hence the three
 * suppressions below - abbreviations, digits before the dot ("in der 6. Klasse", "3.14",
 * "1. Januar"), and single letters ("z.B.", "A. Müller") - all of which over-merge rather than
 * over-split.
 */
public final class SentenceSplitter {

    /** Terminators that can end a sentence. */
    private static final String TERMINATORS = ".!?…";

    /** Characters allowed to trail a terminator while still ending the sentence. */
    private static final String TRAILING = "\"'“”„‘’)]}»«";

    /** Characters allowed to open the next sentence. */
    private static final String OPENING = "\"'“”„‘’([{»«–—-";

    /**
     * Words that end in a dot without ending a sentence. Lowercased, dots stripped, so "z.B." is
     * matched as "zb" and "d.h." as "dh".
     */
    private static final Set<String> ABBREVIATIONS = Set.of(
            "zb", "dh", "ua", "ca", "ggf", "inkl", "exkl", "evtl", "usw", "idr", "vgl", "bzw",
            "bspw", "etc", "ff", "oä", "uu", "zt", "va", "sog", "ggfs", "abs", "art", "nr", "bd",
            "kap", "abb", "tab", "bsp", "dr", "prof", "dipl", "ing", "st", "str", "hr", "fr",
            "mio", "mrd", "jh", "jhd", "min", "max", "vs", "engl", "dt", "lat", "urspr", "eigtl");

    private SentenceSplitter() {
    }

    /**
     * @param text  the text to split
     * @param base  offset of {@code text} within the enclosing document, added to every span
     * @return non-empty sentences as absolute {@code [start, end)} spans, in order
     */
    public static List<Span> split(String text, int base) {
        List<Span> spans = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return spans;
        }
        int sentenceStart = 0;
        for (int i = 0; i < text.length(); i++) {
            if (TERMINATORS.indexOf(text.charAt(i)) < 0) {
                continue;
            }
            if (text.charAt(i) == '.' && suppressedDot(text, i)) {
                continue;
            }
            int end = i + 1;
            while (end < text.length() && TRAILING.indexOf(text.charAt(end)) >= 0) {
                end++;
            }
            int next = end;
            while (next < text.length() && Character.isWhitespace(text.charAt(next))) {
                next++;
            }
            if (next == end && next < text.length()) {
                // no whitespace after the terminator - "3.5" style, not a boundary
                continue;
            }
            if (next < text.length() && !startsSentence(text.charAt(next))) {
                continue;
            }
            addIfNotBlank(spans, text, base, sentenceStart, end);
            sentenceStart = next;
            i = next - 1;
        }
        addIfNotBlank(spans, text, base, sentenceStart, text.length());
        return spans;
    }

    private static void addIfNotBlank(List<Span> spans, String text, int base, int from, int to) {
        int start = from;
        int end = to;
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (start < end) {
            spans.add(new Span(base + start, base + end));
        }
    }

    private static boolean startsSentence(char c) {
        return Character.isUpperCase(c) || Character.isDigit(c) || OPENING.indexOf(c) >= 0;
    }

    /** True when the dot at {@code dot} belongs to an abbreviation, a number or an initial. */
    private static boolean suppressedDot(String text, int dot) {
        if (dot == 0) {
            return true;
        }
        char before = text.charAt(dot - 1);
        if (Character.isDigit(before)) {
            return true;
        }
        int wordStart = dot;
        while (wordStart > 0 && isWordChar(text.charAt(wordStart - 1))) {
            wordStart--;
        }
        String word = text.substring(wordStart, dot);
        if (word.length() == 1 && Character.isLetter(word.charAt(0))) {
            return true;
        }
        return ABBREVIATIONS.contains(word.replace(".", "").toLowerCase());
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '.';
    }

    /** Half-open character range within the normalized document text. */
    public record Span(int start, int end) {
        public Span {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("invalid span [" + start + ", " + end + ")");
            }
        }

        public int length() {
            return end - start;
        }

        public String of(String document) {
            return document.substring(start, end);
        }
    }
}
