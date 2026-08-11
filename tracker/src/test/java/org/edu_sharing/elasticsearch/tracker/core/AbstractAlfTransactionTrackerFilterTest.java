package org.edu_sharing.elasticsearch.tracker.core;

import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link AbstractAlfTransactionTracker#filterIndexableNodes}, the shared node filter/dedup used
 * by SuggestionTracker and RelationTracker. Store filtering is out of scope here - both trackers rely
 * on {@code storeProtocol}/{@code storeIdentifier} being applied server-side (see {@code track()}),
 * the same mechanism {@code content}/{@code preview}/{@code collection}/{@code statisticsalfresco}
 * already use. Previously, neither tracker applied a delete filter at all, and dedup happened on
 * {@code Node} itself, which only works if multiple entries for the same UUID also share the same
 * Alfresco DBID (e.g. the same node touched by several transactions in one batch) - keying on the
 * UUID directly is what actually matches the Elasticsearch {@code _id} being written to, regardless
 * of DBID.
 */
class AbstractAlfTransactionTrackerFilterTest {

    private static Node node(String nodeRef, String status, long id, long txnId) {
        return Node.builder().id(id).nodeRef(nodeRef).status(status).txnId(txnId).build();
    }

    private static AbstractAlfTransactionTracker<AlfTransactionTrackerProperties> tracker() {
        AlfTransactionTrackerProperties props = new AlfTransactionTrackerProperties();
        props.setThreads(1);
        return new AbstractAlfTransactionTracker<>(props) {
            @Override
            public void trackNodes(List<Node> nodes) throws IOException {
                throw new UnsupportedOperationException("not needed for this test");
            }
        };
    }

    @Test
    void skipsDeletedNodes() {
        var tracker = tracker();
        Node deleted = node("workspace://SpacesStore/uuid-1", "d", 1, 1);

        List<Node> result = tracker.filterIndexableNodes(List.of(deleted));

        assertThat(result).isEmpty();
    }

    @Test
    void dedupsByUuidKeepingTheHighestTransactionId() {
        var tracker = tracker();
        Node older = node("workspace://SpacesStore/uuid-1", "u", 1, 5);
        Node newer = node("workspace://SpacesStore/uuid-1", "u", 2, 9);

        List<Node> result = tracker.filterIndexableNodes(List.of(older, newer));

        assertThat(result).containsExactly(newer);
    }
}
