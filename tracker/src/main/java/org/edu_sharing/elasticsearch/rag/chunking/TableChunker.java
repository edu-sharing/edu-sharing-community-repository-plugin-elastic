package org.edu_sharing.elasticsearch.rag.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV and other row-oriented text.
 * <p>
 * Rows are grouped into blocks sized to the token target, and the header row is repeated at the top
 * of every block - a run of rows without its column names is close to meaningless, both to a reader
 * and to an embedding model. Each block is its own hard group, so the packer emits exactly one chunk
 * per block and no overlap is carried across; repeating rows would not add context the way repeating
 * sentences does.
 */
class TableChunker implements Chunker {

    private static final int MIN_DATA_ROWS_PER_BLOCK = 1;

    @Override
    public boolean supports(String mimetype, String normalizedText) {
        if (mimetype == null) {
            return false;
        }
        String type = mimetype.toLowerCase();
        return type.contains("csv") || type.contains("tab-separated") || type.endsWith("/tsv");
    }

    @Override
    public List<Piece> segment(String normalizedText, ChunkingOptions options) {
        List<SentenceSplitter.Span> rows = rowsOf(normalizedText);
        List<Piece> pieces = new ArrayList<>();
        if (rows.isEmpty()) {
            return pieces;
        }

        SentenceSplitter.Span header = rows.get(0);
        int headerTokens = TokenEstimator.estimate(header.of(normalizedText));

        int group = 0;
        int blockTokens = headerTokens;
        int rowsInBlock = 0;
        boolean blockOpen = false;

        for (int i = 1; i < rows.size(); i++) {
            SentenceSplitter.Span row = rows.get(i);
            int rowTokens = TokenEstimator.estimate(row.of(normalizedText));

            if (blockOpen && rowsInBlock >= MIN_DATA_ROWS_PER_BLOCK
                    && blockTokens + rowTokens > options.targetTokens()) {
                group++;
                blockOpen = false;
            }
            if (!blockOpen) {
                pieces.add(new Piece(header, group, Piece.JOIN_LINE, true, null, null, null, null));
                blockTokens = headerTokens;
                rowsInBlock = 0;
                blockOpen = true;
            }
            pieces.add(new Piece(row, group, Piece.JOIN_LINE, false, null, null, null, null));
            blockTokens += rowTokens;
            rowsInBlock++;
        }

        if (!blockOpen) {
            // header-only file: still worth indexing, the column names describe the data
            pieces.add(new Piece(header, group, Piece.JOIN_LINE, true, null, null, null, null));
        }
        return pieces;
    }

    private List<SentenceSplitter.Span> rowsOf(String text) {
        List<SentenceSplitter.Span> rows = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i != text.length() && text.charAt(i) != '\n') {
                continue;
            }
            int s = start;
            int e = i;
            while (s < e && Character.isWhitespace(text.charAt(s))) {
                s++;
            }
            while (e > s && Character.isWhitespace(text.charAt(e - 1))) {
                e--;
            }
            if (s < e) {
                rows.add(new SentenceSplitter.Span(s, e));
            }
            start = i + 1;
        }
        return rows;
    }
}
