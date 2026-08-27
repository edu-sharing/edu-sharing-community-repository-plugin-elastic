package org.edu_sharing.elasticsearch.rag.chunking;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans extracted plain text before it is cut into chunks.
 * <p>
 * Everything downstream - offsets, token estimates, chunk boundaries - refers to the normalized
 * text, so this must be deterministic: the same input always produces the same output, byte for
 * byte. That is what makes the {@code contentHash} skip path in the tracker trustworthy.
 * <p>
 * The form feed {@code \f} is preserved on purpose. Text extractors emit it between pages (and
 * between slides), and it is the only page boundary that survives extraction to plain text - the
 * {@link StructuralChunker} turns it into hard chunk boundaries and page numbers.
 */
public final class TextNormalizer {

    /** Page break as emitted by common text extractors. */
    static final char PAGE_BREAK = '\f';

    /**
     * Below this a line is a heading or a list item, above it the extractor wrapped it. Body text in
     * an A4 layout wraps around 60-80 characters; headings are rarely half that.
     */
    private static final int WRAP_WIDTH_HINT = 45;

    /**
     * Glyphs from symbol fonts (Wingdings bullets and the like) land in the private use area. They
     * carry no meaning, and the model would have to spend attention on them.
     */
    private static final Pattern PRIVATE_USE = Pattern.compile("[\\uE000-\\uF8FF]");

    /**
     * A hyphen at end of line between two lowercase letters is a typesetting artefact
     * ("Bruch-\nrechnung"), so it is joined. Anything involving an uppercase letter is left alone,
     * because there it is far more likely a real compound ("Nord-\nWest").
     */
    private static final Pattern SOFT_HYPHEN_BREAK = Pattern.compile("(\\p{Ll})[-­]\\n(\\p{Ll})");

    /**
     * A line break in the middle of a sentence is where the extractor wrapped the page, not where
     * the author ended a thought.
     * <p>
     * Joined when the line does not end a sentence and either the next line starts lowercase or the
     * line is long enough to have been wrapped. The length test is what makes this work for German:
     * every noun is capitalised, so "in anderen\nBundeslaendern" would survive a lowercase-only rule -
     * measured on real output, that left 8 of 1023 lines still broken. A heading is short and stays
     * on its own line.
     * <p>
     * This reverses an earlier decision to leave single newlines alone, made without ever having seen
     * real extraction output: 2366 of 5561 lines were wrapped mid-sentence, and every one of them
     * ended up in the text handed to the model.
     */
    private static final Pattern WRAPPED_LINE =
            Pattern.compile("(?<=[^\\n.!?:;])[ \\t]*\\n(?=\\p{Ll})");

    /** Same join, for a line long enough that the break can only be a wrap. */
    private static final Pattern WRAPPED_LONG_LINE =
            Pattern.compile("(?m)^(.{" + WRAP_WIDTH_HINT + ",}[^\\n.!?:;])[ \\t]*\\n(?=\\S)");

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t\\f]]");
    private static final Pattern HORIZONTAL_WHITESPACE = Pattern.compile("[ \\t\\u00A0\\u2007\\u202F]+");
    private static final Pattern TRAILING_SPACE = Pattern.compile("[ \\t]+\\n");
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /** How many lines at the top and bottom of a page are considered running head / foot. */
    private static final int EDGE_LINES = 2;

    /** A line has to appear on more than this share of pages to count as a running head / foot. */
    private static final double REPEAT_THRESHOLD = 0.6d;

    /** Below this, "appears on most pages" is not evidence of anything. */
    private static final int MIN_PAGES_FOR_HEADER_DETECTION = 3;

    private TextNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = CONTROL_CHARS.matcher(text).replaceAll("");
        text = SOFT_HYPHEN_BREAK.matcher(text).replaceAll("$1$2");
        text = PRIVATE_USE.matcher(text).replaceAll("");
        text = HORIZONTAL_WHITESPACE.matcher(text).replaceAll(" ");
        text = WRAPPED_LINE.matcher(text).replaceAll(" ");
        text = WRAPPED_LONG_LINE.matcher(text).replaceAll("$1 ");
        // again: joining two lines leaves the trailing and leading space side by side
        text = HORIZONTAL_WHITESPACE.matcher(text).replaceAll(" ");
        text = TRAILING_SPACE.matcher(text).replaceAll("\n");
        text = stripRunningHeadersAndFooters(text);
        text = EXCESS_BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text.strip();
    }

    /**
     * Drops lines that repeat at the top or bottom of most pages - page numbers, document titles,
     * copyright lines. Only runs when the extractor actually gave us page breaks and there are
     * enough pages for repetition to mean something; without that evidence, doing nothing is the
     * safer behaviour.
     * <p>
     * Comparison masks digit runs, so "Seite 3 von 12" and "Seite 4 von 12" count as the same
     * running foot.
     */
    private static String stripRunningHeadersAndFooters(String text) {
        if (text.indexOf(PAGE_BREAK) < 0) {
            return text;
        }
        List<String> pages = List.of(text.split("\f", -1));
        if (pages.size() < MIN_PAGES_FOR_HEADER_DETECTION) {
            return text;
        }

        Map<String, Integer> edgeLineCounts = new HashMap<>();
        List<List<String>> pageLines = new ArrayList<>(pages.size());
        for (String page : pages) {
            List<String> lines = new ArrayList<>(List.of(page.split("\n", -1)));
            pageLines.add(lines);
            // a line counts once per page: on a very short page the same line is both head and foot
            for (String key : new LinkedHashSet<>(edgeLinesOf(lines).stream().map(TextNormalizer::maskDigits).toList())) {
                edgeLineCounts.merge(key, 1, Integer::sum);
            }
        }

        int minOccurrences = (int) Math.floor(pages.size() * REPEAT_THRESHOLD) + 1;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < pageLines.size(); i++) {
            List<String> lines = pageLines.get(i);
            List<String> kept = new ArrayList<>(lines.size());
            for (int l = 0; l < lines.size(); l++) {
                String line = lines.get(l);
                boolean atEdge = l < EDGE_LINES || l >= lines.size() - EDGE_LINES;
                boolean repeats = edgeLineCounts.getOrDefault(maskDigits(line), 0) >= minOccurrences;
                if (atEdge && repeats && !line.isBlank()) {
                    continue;
                }
                kept.add(line);
            }
            if (i > 0) {
                out.append(PAGE_BREAK);
            }
            // A page whose every line looks like a running head is a page of one or two lines, not a
            // page of furniture - dropping all of it would silently delete real content.
            out.append(String.join("\n", kept.stream().anyMatch(l -> !l.isBlank()) ? kept : lines));
        }
        return out.toString();
    }

    private static List<String> edgeLinesOf(List<String> lines) {
        List<String> edges = new ArrayList<>(EDGE_LINES * 2);
        for (int i = 0; i < Math.min(EDGE_LINES, lines.size()); i++) {
            edges.add(lines.get(i));
        }
        for (int i = Math.max(0, lines.size() - EDGE_LINES); i < lines.size(); i++) {
            edges.add(lines.get(i));
        }
        return edges;
    }

    private static String maskDigits(String line) {
        return DIGITS.matcher(line.strip()).replaceAll(Matcher.quoteReplacement("#"));
    }
}
