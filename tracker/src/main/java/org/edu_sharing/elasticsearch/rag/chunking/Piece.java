package org.edu_sharing.elasticsearch.rag.chunking;

/**
 * The smallest unit the packer moves around - normally one sentence, for transcripts one subtitle
 * line, for tables one row.
 * <p>
 * A piece carries its own provenance so that a chunk assembled from several of them can still say
 * where it came from. {@code group} is the hard boundary: pieces from different groups never share a
 * chunk and overlap never crosses between them. Pages, subtitle time windows and table row blocks
 * each open a new group; headings do not - they only set {@code sectionStart}, which the packer
 * treats as a preferred cut, so a document with dense headings does not shatter into fragments.
 *
 * @param span            position in the normalized document text
 * @param group           hard boundary group
 * @param separatorBefore what to put between this piece and the previous one when joining
 * @param sectionStart    true if a heading starts here (soft cut)
 * @param heading         nearest preceding heading, or null
 * @param page            1-based page number, or null if the extractor gave no page breaks
 * @param timeStart       start in seconds for media, or null
 * @param timeEnd         end in seconds for media, or null
 */
record Piece(
        SentenceSplitter.Span span,
        int group,
        String separatorBefore,
        boolean sectionStart,
        String heading,
        Integer page,
        Double timeStart,
        Double timeEnd) {

    static final String JOIN_INLINE = " ";
    static final String JOIN_LINE = "\n";
    static final String JOIN_BLOCK = "\n\n";

    String text(String document) {
        return span.of(document);
    }

    int tokens(String document) {
        return TokenEstimator.estimate(text(document));
    }

    Piece withSpan(SentenceSplitter.Span newSpan) {
        return new Piece(newSpan, group, separatorBefore, sectionStart, heading, page, timeStart, timeEnd);
    }
}
