package org.edu_sharing.elasticsearch.rag.chunking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Hash over everything about a node that would change its chunks or their embeddings.
 * <p>
 * This is what lets the tracker skip the expensive part: if the fingerprint is unchanged, the
 * vectors in the index are still correct and no embedding call is needed. ACL, collection and
 * statistics updates touch nodes constantly without changing a character of any of this, and those
 * are precisely the writes this is meant to absorb.
 * <p>
 * <strong>It deliberately covers more than the full text.</strong> Every content chunk is embedded
 * with a context header built from title, subject and educational context, and the metadata chunk is
 * built from those plus description, keywords and resource type. Hashing only the full text would
 * mean a renamed title leaves every vector of that node silently stale - the stored text would be
 * right while the vector still answers for the old title.
 * <p>
 * Chunking parameters are <em>not</em> part of the hash: they belong to the embedding profile, and a
 * profile change already means a separate index that is built from scratch anyway.
 */
public final class ContentFingerprint {

    /**
     * Bumped when the set of hashed inputs changes, so a hash written by an older build can never
     * accidentally compare equal to one computed over a different set of fields.
     */
    private static final String SCHEME = "v1";

    private ContentFingerprint() {
    }

    public static String of(ChunkSource source) {
        StringBuilder input = new StringBuilder(SCHEME);
        appendField(input, TextNormalizer.normalize(source.fullText()));
        appendField(input, source.title());
        appendField(input, source.description());
        appendList(input, source.keywords());
        appendList(input, source.subject());
        appendList(input, source.educationalContext());
        appendList(input, source.learningResourceType());
        return sha256(input.toString());
    }

    /**
     * Length-prefixed rather than delimited. A separator character would have to be one that cannot
     * occur in the input, and there is no such character in extracted document text; a length prefix
     * needs no such assumption and still makes ("ab", "c") and ("a", "bc") hash differently.
     */
    private static void appendField(StringBuilder input, String value) {
        String text = value == null ? "" : value;
        input.append(text.length()).append(':').append(text);
    }

    /**
     * Values keep their given order. Reordering them is a real metadata change - it changes the
     * metadata chunk's text - so it should change the fingerprint too.
     */
    private static void appendList(StringBuilder input, List<String> values) {
        input.append(values.size()).append(';');
        for (String value : values) {
            appendField(input, value);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
