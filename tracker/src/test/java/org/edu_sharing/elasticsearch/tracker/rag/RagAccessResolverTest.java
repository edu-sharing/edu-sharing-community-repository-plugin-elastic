package org.edu_sharing.elasticsearch.tracker.rag;

import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkAccess;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins down what the resolver flattens out of a workspace document.
 * <p>
 * The distinctions here are the ones an earlier version lost by storing a single union of
 * authorities. That union was too permissive in two specific ways, and neither would have failed
 * anything: a proposal would have been visible to everyone allowed to read the collection instead of
 * to its coordinators, and a node marked {@code ccm:restricted_access} would have inherited
 * collection rights it is meant not to inherit.
 * <p>
 * The resolver stores inputs, never a verdict - {@code SearchServiceElastic} combines them. These
 * tests therefore assert which value lands in which field, not who ends up seeing what.
 */
class RagAccessResolverTest {

    /** The mapping step is deliberately private; it has no dependencies worth a seam of its own. */
    private static RagChunkAccess resolve(Map<String, Object> source) throws Exception {
        Method m = RagAccessResolver.class.getDeclaredMethod("toAccess", Map.class);
        m.setAccessible(true);
        return (RagChunkAccess) m.invoke(null, source);
    }

    private static Map<String, Object> collection(String uuid, String relation, String owner,
                                                  List<String> read, List<String> coordinator) {
        Map<String, Object> permissions = new LinkedHashMap<>();
        if (read != null) {
            permissions.put("read", read);
        }
        if (coordinator != null) {
            permissions.put("Coordinator", coordinator);
        }
        return Map.of(
                "nodeRef", Map.of("id", uuid),
                "owner", owner,
                "relation", Map.of("type", relation),
                "permissions", permissions);
    }

    private static Map<String, Object> node(Object collections, Map<String, Object> properties) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("permissions", Map.of("read", List.of("GROUP_EVERYONE")));
        if (collections != null) {
            source.put("collections", collections);
        }
        if (properties != null) {
            source.put("properties", properties);
        }
        return source;
    }

    @Test
    void keepsTheNodesOwnReadersSeparate() throws Exception {
        RagChunkAccess access = resolve(node(
                List.of(collection("coll-1", "ccm:usage", "admin", List.of("GROUP_A"), null)), null));

        assertThat(access.readers()).containsExactly("GROUP_EVERYONE");
        assertThat(access.collectionReaders()).containsExactly("GROUP_A");
    }

    @Test
    void treatsAProposalAsCoordinatorsOnly() throws Exception {
        // the case the union got wrong: a proposal is not visible to everyone who may read the
        // collection, only to whoever coordinates it
        RagChunkAccess access = resolve(node(List.of(
                collection("coll-1", "ccm:collection_proposal", "admin",
                        List.of("GROUP_LESER"), List.of("GROUP_KOORDINATOR"))), null));

        assertThat(access.proposalCoordinators()).containsExactly("GROUP_KOORDINATOR");
        assertThat(access.collectionReaders())
                .as("a proposal's readers must not become collection readers")
                .isEmpty();
    }

    @Test
    void keepsUsageAndProposalApartOnTheSameNode() throws Exception {
        RagChunkAccess access = resolve(node(List.of(
                collection("coll-1", "ccm:usage", "a", List.of("GROUP_A"), List.of("GROUP_X")),
                collection("coll-2", "ccm:collection_proposal", "b", List.of("GROUP_B"), List.of("GROUP_Y"))), null));

        assertThat(access.collectionReaders()).containsExactly("GROUP_A");
        assertThat(access.proposalCoordinators()).containsExactly("GROUP_Y");
        assertThat(access.collectionOwners()).containsExactlyInAnyOrder("a", "b");
        assertThat(access.collections()).containsExactlyInAnyOrder("coll-1", "coll-2");
    }

    @Test
    void carriesTheRestrictedAccessFlags() throws Exception {
        // stored as text in the workspace index, so the value arrives as a string
        RagChunkAccess restricted = resolve(node(null, Map.of(
                "ccm:restricted_access", "true",
                "ccm:restricted_access_permissions", List.of("ReadAll"))));

        assertThat(restricted.restrictedAccess()).isTrue();
        assertThat(restricted.restrictedReadAll()).isTrue();
    }

    @Test
    void leavesTheFlagsOffWhenThePropertyIsAbsent() throws Exception {
        RagChunkAccess access = resolve(node(null, null));

        assertThat(access.restrictedAccess()).isFalse();
        assertThat(access.restrictedReadAll()).isFalse();
    }

    @Test
    void doesNotGrantReadAllFromAnyOtherPermission() throws Exception {
        RagChunkAccess access = resolve(node(null, Map.of(
                "ccm:restricted_access", "true",
                "ccm:restricted_access_permissions", List.of("Consumer"))));

        assertThat(access.restrictedAccess()).isTrue();
        assertThat(access.restrictedReadAll()).isFalse();
    }

    @Test
    void ignoresACollectionWithAnUnknownRelation() throws Exception {
        // an unrecognised relation grants nothing rather than everything
        RagChunkAccess access = resolve(node(
                List.of(collection("coll-1", "ccm:something_new", "admin", List.of("GROUP_A"), List.of("GROUP_B"))), null));

        assertThat(access.collectionReaders()).isEmpty();
        assertThat(access.proposalCoordinators()).isEmpty();
        assertThat(access.collections()).isEmpty();
    }

    @Test
    void survivesANodeWithoutCollections() throws Exception {
        RagChunkAccess access = resolve(node(null, null));

        assertThat(access.readers()).containsExactly("GROUP_EVERYONE");
        assertThat(access.collectionReaders()).isEmpty();
        assertThat(access.collections()).isEmpty();
    }
}
