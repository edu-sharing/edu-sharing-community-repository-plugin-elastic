package org.edu_sharing.elasticsearch.alfresco.client;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class NodeEqualsHashCodeTest {

    @Test
    void equalNodesHaveEqualHashCodes() {
        // Node overrides equals() by DBID; Lombok therefore does not generate hashCode() for it,
        // leaving the identity hash - which silently broke dedup in HashMap/HashSet-based collections
        // (e.g. the suggestion/relation tracker's per-node result map).
        Node a = Node.builder().id(1).nodeRef("workspace://SpacesStore/uuid-1").status("u").txnId(1).build();
        Node b = Node.builder().id(1).nodeRef("workspace://SpacesStore/uuid-1").status("u").txnId(2).build();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());

        HashSet<Node> set = new HashSet<>();
        set.add(a);
        set.add(b);
        assertThat(set).hasSize(1);
    }

    @Test
    void differentIdsHaveDifferentEqualityButMayStillCollideOnHashCode() {
        Node a = Node.builder().id(1).nodeRef("workspace://SpacesStore/uuid-1").status("u").txnId(1).build();
        Node b = Node.builder().id(2).nodeRef("workspace://SpacesStore/uuid-1").status("u").txnId(1).build();

        assertThat(a).isNotEqualTo(b);
    }
}
