package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.elasticsearch.utils.utils.NodeMetadataSimple;
import org.edu_sharing.elasticsearch.tools.Tools;

/**
 * this class holds all queries that might be send to the elastic from the tracker
 * Check this class in case the elastic model gets changed!
 */
public class InternalQueries {
    public static Query queryByUUID(String value, String result) {
        return Query.of(q -> q.term(t -> t.field(value).value(result)));
    }

    public static Query queryCollectionNodes(Long collectionDbid) {
        return Query.of(q -> q.nested(n -> n.path("collections").query(Query.of(qi -> qi.term(t -> t.field("collections.dbid").value(collectionDbid))))));
    }

    public static Query queryChildrenNodes(Long childDbid) {
        return Query.of(q -> q.nested(n -> n.path("children").query(Query.of(qi -> qi.term(t -> t.field("children.dbid").value(childDbid))))));
    }

    public static Query queryCollectionNodesViaUsage(Node node) {
        return Query.of(q -> q.nested(n -> n.path("collections").query(Query.of(qi -> qi.term(t -> t.field("collections.relation.dbid").value(node.getId()))))));
    }

    public static Query queryUsages(NodeMetadataSimple node, String query) {
        return Query.of(q -> q.bool(b -> b
                .must(m -> m.term(t -> t.field(query).value(Tools.getUUID(node.getNodeRef()))))
                .must(m -> m.term(t -> t.field("type").value("ccm:usage")))));
    }

    public static Query queryByUUID(String uuid, String protocol, String identifier) {
        return Query.of(q -> q.bool(b -> b
                .must(must -> must.term(t -> t.field("nodeRef.id").value(uuid)))
                .must(must -> must.term(t -> t.field("nodeRef.storeRef.protocol").value(protocol)))
                .must(must -> must.term(t -> t.field("nodeRef.storeRef.identifier").value(identifier)))));
    }


    public static Query queryProposals(NodeMetadataSimple node, String queryProposal) {
        final Query queryProposalBase = ("ccm:io".equals(node.getType()))
                ? queryByUUID(queryProposal, node.getNodeRef())
                : queryByUUID(queryProposal, Tools.getUUID(node.getNodeRef()));

        return Query.of(q -> q.bool(b -> b.must(queryProposalBase).must(m -> m.term(t -> t.field("type").value("ccm:collection_proposal")))));
    }
}
