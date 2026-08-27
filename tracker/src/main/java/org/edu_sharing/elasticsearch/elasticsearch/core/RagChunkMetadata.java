package org.edu_sharing.elasticsearch.elasticsearch.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The part of a chunk document that can change without changing a single vector.
 * <p>
 * Kept as its own type because it drives the cheap update path: a licence correction, a move, a new
 * collection membership all have to reach the index so filters stay right, but none of them is a
 * reason to call the embedding model again.
 * <p>
 * Deliberately without {@code txnId}. Storing it would make {@link #fingerprint()} differ on every
 * transaction that touches the node, which is exactly the case this exists to skip - and nothing
 * queries it here. The workspace index keeps it for that kind of forensics.
 * <p>
 * Access is not here either - see {@link RagChunkAccess}. It changes on events this tracker never
 * sees (an ACL change is not a node transaction) and therefore has its own writer; sharing a
 * fingerprint with it would let the two paths overwrite each other's marker.
 */
public record RagChunkMetadata(
        Long dbid,
        Long aclId,
        String owner,
        String type,
        List<String> aspects,
        String fullpath,
        List<String> fullpaths,
        RagChunkDocument.Facets facets,
        Map<String, Object> facetsFlat) {

    /**
     * Canonical serialisation for hashing: map keys sorted, nulls omitted. Records already serialise
     * in declaration order, so the remaining source of instability is map iteration order.
     */
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /**
     * Hash over everything here, used the way {@code ContentFingerprint} is used for the text: if it
     * is unchanged, the chunk documents already say the right thing and nothing has to be written at
     * all. Without this second hash every transaction touching a node would rewrite all of its
     * chunks - and in an index holding vectors, rewriting a document is the expensive operation.
     */
    public String fingerprint() {
        try {
            byte[] canonical = CANONICAL.writeValueAsBytes(this);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("chunk metadata is not serialisable", e);
        }
    }
}
