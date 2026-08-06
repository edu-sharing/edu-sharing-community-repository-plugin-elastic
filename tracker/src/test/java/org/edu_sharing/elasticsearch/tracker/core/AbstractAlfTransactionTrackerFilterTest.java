package org.edu_sharing.elasticsearch.tracker.core;

import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link AbstractAlfTransactionTracker#filterIndexableNodes}, the shared node filter/dedup
 * used by SuggestionTracker and RelationTracker. Previously, neither tracker applied
 * {@code indexStoreRefs} or a delete filter at all, and dedup happened on {@code Node} itself, which
 * only works if multiple entries for the same UUID also share the same Alfresco DBID (e.g. the same
 * node touched by several transactions in one batch) - keying on the UUID directly is what actually
 * matches the Elasticsearch {@code _id} being written to, regardless of DBID.
 */
class AbstractAlfTransactionTrackerFilterTest {

    private static Node node(String nodeRef, String status, long id, long txnId) {
        return Node.builder().id(id).nodeRef(nodeRef).status(status).txnId(txnId).build();
    }

    private static AbstractAlfTransactionTracker<AlfTransactionTrackerProperties> trackerWithStores(List<String> indexStoreRefs) {
        AlfTransactionTrackerProperties props = new AlfTransactionTrackerProperties();
        props.setThreads(1);
        props.setIndexStoreRefs(indexStoreRefs);
        return new AbstractAlfTransactionTracker<>(props) {
            @Override
            public void trackNodes(List<Node> nodes) throws IOException {
                throw new UnsupportedOperationException("not needed for this test");
            }
        };
    }

    @Test
    void keepsOnlyConfiguredStores() {
        var tracker = trackerWithStores(List.of("workspace://SpacesStore"));
        Node live = node("workspace://SpacesStore/uuid-1", "u", 1, 1);
        Node archived = node("archive://SpacesStore/uuid-2", "u", 2, 1);

        List<Node> result = tracker.filterIndexableNodes(List.of(live, archived));

        assertThat(result).containsExactly(live);
    }

    @Test
    void fallsBackToWorkspaceSpacesWhenNoStoresAreConfigured() {
        // an empty/missing indexStoreRefs property must not silently let everything through
        // (that was the bug: the property existed but nothing ever read it for this tracker).
        var tracker = trackerWithStores(List.of());
        Node live = node("workspace://SpacesStore/uuid-1", "u", 1, 1);
        Node archived = node("archive://SpacesStore/uuid-2", "u", 2, 1);

        List<Node> result = tracker.filterIndexableNodes(List.of(live, archived));

        assertThat(result).containsExactly(live);
    }

    @Test
    void skipsDeletedNodes() {
        var tracker = trackerWithStores(List.of("workspace://SpacesStore"));
        Node deleted = node("workspace://SpacesStore/uuid-1", "d", 1, 1);

        List<Node> result = tracker.filterIndexableNodes(List.of(deleted));

        assertThat(result).isEmpty();
    }

    @Test
    void dedupsByUuidKeepingTheHighestTransactionId() {
        var tracker = trackerWithStores(List.of("workspace://SpacesStore"));
        Node older = node("workspace://SpacesStore/uuid-1", "u", 1, 5);
        Node newer = node("workspace://SpacesStore/uuid-1", "u", 2, 9);

        List<Node> result = tracker.filterIndexableNodes(List.of(older, newer));

        assertThat(result).containsExactly(newer);
    }
}
