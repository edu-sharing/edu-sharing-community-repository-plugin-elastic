package org.edu_sharing.elasticsearch.elasticsearch.core;

import java.util.List;

/**
 * The inputs the read rule needs, flattened out of the workspace document.
 * <p>
 * <strong>Inputs, not a decision.</strong> An earlier version stored a single {@code readers} union
 * and was wrong for it: edu-sharing's read rule is richer than "node readers plus collection
 * readers". A node marked {@code ccm:restricted_access} does <em>not</em> inherit collection rights
 * unless {@code ReadAll} is granted, and a {@code ccm:collection_proposal} is visible only to
 * coordinators of that collection, never to everyone allowed to read it. A union silently granted
 * both - too permissive, in an access filter.
 * <p>
 * So the rule stays where it already lives, in {@code SearchServiceElastic}, and this only supplies
 * the values it combines. Change the permission semantics of edu-sharing and only the query side
 * moves; nothing here has to know what the rule currently is.
 * <p>
 * Every field is flat on purpose. The kNN filter is a pre-filter walked during the HNSW traversal,
 * where a {@code nested} query beside a dense vector is both heavy and awkward - which is why the
 * workspace index's nested {@code collections} are not reproduced here.
 *
 * @param readers              the node's own {@code permissions.read}
 * @param collectionReaders    read authorities of collections holding a copy via {@code ccm:usage}
 * @param proposalCoordinators coordinators of collections holding a {@code ccm:collection_proposal}
 * @param collectionOwners     owners of either kind of collection
 * @param collections          UUIDs of those collections, so a search can be scoped to one
 * @param restrictedAccess     {@code ccm:restricted_access} - blocks inheriting collection rights
 * @param restrictedReadAll    {@code ccm:restricted_access_permissions} contains {@code ReadAll},
 *                             which lifts that block again
 */
public record RagChunkAccess(
        List<String> readers,
        List<String> collectionReaders,
        List<String> proposalCoordinators,
        List<String> collectionOwners,
        List<String> collections,
        boolean restrictedAccess,
        boolean restrictedReadAll) {

    public static final RagChunkAccess NONE =
            new RagChunkAccess(List.of(), List.of(), List.of(), List.of(), List.of(), false, false);

    public RagChunkAccess {
        readers = copy(readers);
        collectionReaders = copy(collectionReaders);
        proposalCoordinators = copy(proposalCoordinators);
        collectionOwners = copy(collectionOwners);
        collections = copy(collections);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
