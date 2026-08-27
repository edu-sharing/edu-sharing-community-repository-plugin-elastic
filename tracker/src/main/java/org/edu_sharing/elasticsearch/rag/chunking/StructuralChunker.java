package org.edu_sharing.elasticsearch.rag.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The general case: PDF, DOCX, HTML and anything else that came out of text extraction as prose.
 * <p>
 * Cuts at page breaks first (hard), then at headings (soft), then at blank lines, then at sentence
 * boundaries - in that order of preference, so a chunk gives up the largest structure last. Page
 * numbers are only produced when the extractor actually emitted {@code \f}; deriving them from line
 * counts would be a guess, and a wrong page reference in a citation is worse than none at all.
 */
class StructuralChunker implements Chunker {

    /** Blank line, i.e. paragraph boundary. */
    private static final Pattern BLOCK_SEPARATOR = Pattern.compile("\\n[ \\t]*\\n");

    /** Numbered heading: "3", "3.1", "3.1.2" followed by a title. */
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^\\d+(\\.\\d+)*\\.?\\s+\\S.*");

    /** Longer than this and a single line is a paragraph that happens to lack punctuation. */
    private static final int MAX_HEADING_LENGTH = 120;

    /** A heading is a title, not a sentence, so it does not end like one. */
    private static final String SENTENCE_ENDINGS = ".!?…:;,";

    @Override
    public boolean supports(String mimetype, String normalizedText) {
        return true;
    }

    @Override
    public List<Piece> segment(String normalizedText, ChunkingOptions options) {
        List<Piece> pieces = new ArrayList<>();
        boolean paged = normalizedText.indexOf(TextNormalizer.PAGE_BREAK) >= 0;

        String[] pages = normalizedText.split("\f", -1);
        int offset = 0;
        for (int pageIndex = 0; pageIndex < pages.length; pageIndex++) {
            String page = pages[pageIndex];
            segmentPage(page, offset, pageIndex, paged ? pageIndex + 1 : null, pieces);
            offset += page.length() + 1; // + the single form feed we split on
        }
        return pieces;
    }

    private void segmentPage(String page, int pageOffset, int group, Integer pageNumber, List<Piece> out) {
        String heading = null;
        for (Block block : blocksOf(page)) {
            String body = block.text();
            if (body.isBlank()) {
                continue;
            }
            int absoluteStart = pageOffset + block.start();

            boolean isHeading = isHeading(body);
            if (isHeading) {
                heading = body.strip();
            }

            List<SentenceSplitter.Span> sentences = SentenceSplitter.split(body, absoluteStart);
            for (int i = 0; i < sentences.size(); i++) {
                out.add(new Piece(
                        sentences.get(i),
                        group,
                        i == 0 ? Piece.JOIN_BLOCK : Piece.JOIN_INLINE,
                        isHeading && i == 0,
                        heading,
                        pageNumber,
                        null,
                        null));
            }
        }
    }

    /** Splits on blank lines while keeping every block's exact offset within {@code page}. */
    private List<Block> blocksOf(String page) {
        List<Block> blocks = new ArrayList<>();
        Matcher matcher = BLOCK_SEPARATOR.matcher(page);
        int start = 0;
        while (matcher.find()) {
            blocks.add(new Block(start, page.substring(start, matcher.start())));
            start = matcher.end();
        }
        blocks.add(new Block(start, page.substring(start)));
        return blocks;
    }

    /**
     * A block is a heading when it is a single short line that does not read like a sentence:
     * either explicitly numbered, or free of sentence-ending punctuation.
     */
    private boolean isHeading(String block) {
        String line = block.strip();
        if (line.isEmpty() || line.indexOf('\n') >= 0 || line.length() > MAX_HEADING_LENGTH) {
            return false;
        }
        if (NUMBERED_HEADING.matcher(line).matches()) {
            return true;
        }
        char last = line.charAt(line.length() - 1);
        return SENTENCE_ENDINGS.indexOf(last) < 0 && Character.isLetterOrDigit(line.charAt(0));
    }

    private record Block(int start, String text) {
    }
}
