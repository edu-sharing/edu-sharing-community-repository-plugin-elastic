package org.edu_sharing.elasticsearch.rag.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * Packs {@link Piece}s into chunks of uniform size, so that chunk length does not depend on which
 * {@link Chunker} produced the pieces.
 * <p>
 * Four passes, in this order: oversized pieces are split, pieces are packed greedily towards the
 * target, undersized chunks are merged into a neighbour, and finally the tail of each chunk is
 * repeated at the head of the next one. Overlap comes last on purpose - it has to see the final
 * chunk boundaries, and it is the only pass allowed to push a chunk past
 * {@link ChunkingOptions#targetTokens()} (never past {@code maxTokens}).
 * <p>
 * Nothing here crosses a {@link Piece#group()} boundary: pages, subtitle windows and table blocks
 * stay separate, including for overlap.
 */
class ChunkPacker {

    /** A heading only forces a cut once the current chunk has some substance. */
    private static final double SECTION_CUT_FILL_RATIO = 0.5d;

    List<Chunk> pack(String document, List<Piece> pieces, ChunkingOptions options) {
        List<Chunk> chunks = new ArrayList<>();
        for (List<Piece> group : groupsOf(splitOversized(document, pieces, options))) {
            List<Draft> drafts = packGroup(group, options);
            mergeUndersized(drafts, options);
            addOverlap(drafts, options);
            for (Draft draft : drafts) {
                chunks.add(draft.toChunk(document));
            }
        }
        return chunks;
    }

    // ---- pass 1: nothing may be larger than the ceiling on its own ----

    private List<Piece> splitOversized(String document, List<Piece> pieces, ChunkingOptions options) {
        int maxChars = TokenEstimator.charsFor(options.maxTokens());
        List<Piece> out = new ArrayList<>(pieces.size());
        for (Piece piece : pieces) {
            if (piece.span().length() <= maxChars) {
                out.add(piece);
                continue;
            }
            out.addAll(splitAtWordBoundaries(document, piece, maxChars));
        }
        return out;
    }

    /**
     * A single "sentence" longer than the ceiling means the splitter found no boundary - a table of
     * contents without punctuation, or broken extraction. Cut at the last space before the limit so
     * at least no word is torn apart.
     */
    private List<Piece> splitAtWordBoundaries(String document, Piece piece, int maxChars) {
        List<Piece> out = new ArrayList<>();
        int start = piece.span().start();
        int end = piece.span().end();
        while (start < end) {
            int limit = Math.min(start + maxChars, end);
            int cut = limit;
            if (limit < end) {
                int space = document.lastIndexOf(' ', limit);
                if (space > start) {
                    cut = space;
                }
            }
            out.add(piece.withSpan(new SentenceSplitter.Span(start, cut)));
            start = cut;
            while (start < end && document.charAt(start) == ' ') {
                start++;
            }
        }
        return out;
    }

    private List<List<Piece>> groupsOf(List<Piece> pieces) {
        List<List<Piece>> groups = new ArrayList<>();
        List<Piece> current = new ArrayList<>();
        Integer currentId = null;
        for (Piece piece : pieces) {
            if (currentId != null && piece.group() != currentId) {
                groups.add(current);
                current = new ArrayList<>();
            }
            currentId = piece.group();
            current.add(piece);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    // ---- pass 2: greedy fill towards the target ----

    private List<Draft> packGroup(List<Piece> group, ChunkingOptions options) {
        List<Draft> drafts = new ArrayList<>();
        Draft current = new Draft();
        int sectionCutThreshold = (int) (options.targetTokens() * SECTION_CUT_FILL_RATIO);

        for (Piece piece : group) {
            if (!current.isEmpty()) {
                boolean sectionCut = piece.sectionStart() && current.tokens() >= sectionCutThreshold;
                boolean wouldOverflow = current.tokensWith(piece) > options.targetTokens();
                if (sectionCut || wouldOverflow) {
                    drafts.add(current);
                    current = new Draft();
                }
            }
            current.add(piece);
        }
        if (!current.isEmpty()) {
            drafts.add(current);
        }
        return drafts;
    }

    // ---- pass 3: no stranded fragments ----

    private void mergeUndersized(List<Draft> drafts, ChunkingOptions options) {
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            if (draft.tokens() >= options.minTokens() || drafts.size() == 1) {
                continue;
            }
            if (i > 0 && drafts.get(i - 1).tokensAfterAppending(draft) <= options.maxTokens()) {
                drafts.get(i - 1).append(draft);
                drafts.remove(i);
                i--;
            } else if (i + 1 < drafts.size() && draft.tokensAfterAppending(drafts.get(i + 1)) <= options.maxTokens()) {
                draft.append(drafts.get(i + 1));
                drafts.remove(i + 1);
            }
        }
    }

    // ---- pass 4: repeat the tail of the previous chunk ----

    private void addOverlap(List<Draft> drafts, ChunkingOptions options) {
        if (options.overlapTokens() <= 0) {
            return;
        }
        List<List<Piece>> tails = new ArrayList<>(drafts.size());
        for (Draft draft : drafts) {
            tails.add(draft.tail(options.overlapTokens()));
        }
        for (int i = 1; i < drafts.size(); i++) {
            drafts.get(i).prepend(tails.get(i - 1), options.maxTokens());
        }
    }

    /**
     * A chunk under construction. Tracks its own character count so token estimates stay O(1), and
     * remembers where the prepended overlap ends - the chunk's heading and page come from its own
     * content, not from the tail of the previous section that happens to sit in front of it.
     */
    private static final class Draft {
        private final List<Piece> pieces = new ArrayList<>();
        private int chars;
        private int ownContentStart;

        boolean isEmpty() {
            return pieces.isEmpty();
        }

        int tokens() {
            return TokenEstimator.fromChars(chars);
        }

        int tokensWith(Piece piece) {
            return TokenEstimator.fromChars(chars + contribution(piece, pieces.isEmpty()));
        }

        int tokensAfterAppending(Draft other) {
            return TokenEstimator.fromChars(chars + other.charsWhenNotFirst());
        }

        /** What a piece adds to the joined text, including its separator unless it leads. */
        private static int contribution(Piece piece, boolean first) {
            return (first ? 0 : piece.separatorBefore().length()) + piece.span().length();
        }

        /** This draft's length if it were appended to something, i.e. nothing leads. */
        private int charsWhenNotFirst() {
            return pieces.isEmpty() ? 0 : chars + pieces.get(0).separatorBefore().length();
        }

        void add(Piece piece) {
            chars += contribution(piece, pieces.isEmpty());
            pieces.add(piece);
        }

        void append(Draft other) {
            for (Piece piece : other.pieces) {
                add(piece);
            }
        }

        /** Trailing pieces worth at most {@code budget} tokens, never the whole chunk. */
        List<Piece> tail(int budget) {
            List<Piece> tail = new ArrayList<>();
            int budgetChars = TokenEstimator.charsFor(budget);
            int used = 0;
            for (int i = pieces.size() - 1; i > 0; i--) {
                int length = contribution(pieces.get(i), false);
                if (used + length > budgetChars) {
                    break;
                }
                used += length;
                tail.add(0, pieces.get(i));
            }
            return tail;
        }

        void prepend(List<Piece> overlap, int maxTokens) {
            if (overlap.isEmpty() || pieces.isEmpty()) {
                return;
            }
            List<Piece> merged = new ArrayList<>(overlap.size() + pieces.size());
            merged.addAll(overlap);
            merged.addAll(pieces);
            int mergedChars = charsOf(merged);
            if (TokenEstimator.fromChars(mergedChars) > maxTokens) {
                return;
            }
            pieces.clear();
            pieces.addAll(merged);
            chars = mergedChars;
            ownContentStart = overlap.size();
        }

        private static int charsOf(List<Piece> pieces) {
            int total = 0;
            for (int i = 0; i < pieces.size(); i++) {
                total += contribution(pieces.get(i), i == 0);
            }
            return total;
        }

        Chunk toChunk(String document) {
            StringBuilder text = new StringBuilder();
            int charStart = Integer.MAX_VALUE;
            int charEnd = Integer.MIN_VALUE;
            Double timeStart = null;
            Double timeEnd = null;

            for (int i = 0; i < pieces.size(); i++) {
                Piece piece = pieces.get(i);
                if (i > 0) {
                    text.append(piece.separatorBefore());
                }
                text.append(piece.text(document));
                charStart = Math.min(charStart, piece.span().start());
                charEnd = Math.max(charEnd, piece.span().end());
                if (piece.timeStart() != null) {
                    timeStart = timeStart == null ? piece.timeStart() : Math.min(timeStart, piece.timeStart());
                }
                if (piece.timeEnd() != null) {
                    timeEnd = timeEnd == null ? piece.timeEnd() : Math.max(timeEnd, piece.timeEnd());
                }
            }

            Piece anchor = pieces.get(Math.min(ownContentStart, pieces.size() - 1));
            return new Chunk(0, ChunkKind.CONTENT, text.toString(), "",
                    charStart, charEnd, anchor.heading(), anchor.page(), timeStart, timeEnd);
        }
    }
}
