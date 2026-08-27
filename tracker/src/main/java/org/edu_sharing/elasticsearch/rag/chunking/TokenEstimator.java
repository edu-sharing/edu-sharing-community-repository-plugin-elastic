package org.edu_sharing.elasticsearch.rag.chunking;

/**
 * Cheap stand-in for the model's real tokenizer.
 * <p>
 * Running the actual tokenizer would mean shipping the model's vocabulary into the tracker for a
 * number that only steers a quality heuristic: the inference service enforces its own hard limit and
 * truncates anything too long. German averages roughly 3.6 characters per token across the
 * SentencePiece vocabularies used by the multilingual models in question, which is close enough to
 * pack chunks towards a target size.
 */
public final class TokenEstimator {

    /** Characters per token for German prose. */
    private static final double CHARS_PER_TOKEN = 3.6d;

    private TokenEstimator() {
    }

    /** Estimated token count, never negative, and never 0 for a non-empty string. */
    public static int estimate(CharSequence text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / CHARS_PER_TOKEN));
    }

    /** Characters that fit into the given token budget - the inverse of {@link #estimate}. */
    public static int charsFor(int tokens) {
        return Math.max(0, (int) Math.floor(tokens * CHARS_PER_TOKEN));
    }

    /**
     * Same estimate from a character count alone. The packer tracks its running length as it adds
     * pieces, so it never has to materialise a candidate chunk just to measure it.
     */
    public static int fromChars(int chars) {
        if (chars <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(chars / CHARS_PER_TOKEN));
    }
}
