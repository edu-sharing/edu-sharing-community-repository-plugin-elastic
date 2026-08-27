package org.edu_sharing.elasticsearch.tracker.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.elasticsearch.core.InternalQueries;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkAccess;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads who may see a node, from the workspace index.
 * <p>
 * The union is recomputed here rather than patched into the chunk index, and that is the whole
 * design decision: a flat list of authorities cannot be maintained incrementally, because nothing in
 * it says which authority came from which source. Storing that provenance would make removal
 * possible but introduces a failure mode worth avoiding - a stale entry (a copy deleted, a
 * collection's ACL tightened, the node's own acl id changed) would keep granting access, and drift
 * in an access projection is invisible until someone sees something they should not.
 * <p>
 * Recomputing has neither problem. Nothing is subtracted, so an authority that lost access is simply
 * absent from the next resolution, and there is no state here that could drift: the workspace index
 * remains the single source of truth and this is a projection of it.
 * <p>
 * The price is one batched read per write. The caller must ensure the workspace index already
 * reflects the change - in practice, that the RAG refresh runs after the workspace update and its
 * refresh, not beside it.
 */
@Slf4j
public class RagAccessResolver {

    /** Sorting on a long that every workspace document carries makes paging total and stable. */
    private static final String PAGE_SORT_FIELD = "dbid";

    private static final int PAGE_SIZE = 1000;

    /** A copy in a collection - visible to whoever may read that collection. */
    private static final String RELATION_USAGE = "ccm:usage";

    /** A proposal - visible only to coordinators of the collection, never to its readers. */
    private static final String RELATION_PROPOSAL = "ccm:collection_proposal";

    /** {@code CCConstants.PERMISSION_READ_ALL}; not on this module's classpath. */
    private static final String PERMISSION_READ_ALL = "ReadAll";

    private final ElasticsearchClient client;
    private final String workspaceIndex;

    public RagAccessResolver(ElasticsearchClient client, IndexConfiguration workspace) {
        this.client = client;
        this.workspaceIndex = workspace.getIndex();
    }

    /**
     * The effective access of each node, keyed by node UUID.
     * <p>
     * Nodes missing from the workspace index are absent from the result - the caller decides what
     * that means, but it is normally a node the main tracker has not written yet.
     */
    public Map<String, RagChunkAccess> resolve(Collection<String> nodeIds) throws IOException {
        if (nodeIds.isEmpty()) {
            return Map.of();
        }
        List<FieldValue> values = nodeIds.stream().map(FieldValue::of).toList();
        SearchResponse<Map> response = client.search(req -> req
                        .index(workspaceIndex)
                        .size(nodeIds.size())
                        .query(q -> q.terms(t -> t.field("nodeRef.id").terms(v -> v.value(values))))
                        .source(s -> s.filter(f -> f.includes(
                                "nodeRef.id",
                                "permissions.read",
                                "collections.nodeRef.id",
                                "collections.owner",
                                "collections.relation.type",
                                "collections.permissions.read",
                                "collections.permissions.Coordinator",
                                "properties.ccm:restricted_access",
                                "properties.ccm:restricted_access_permissions"))),
                Map.class);

        Map<String, RagChunkAccess> access = new HashMap<>();
        for (Hit<Map> hit : response.hits().hits()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> source = hit.source();
            if (source == null) {
                continue;
            }
            access.put(hit.id(), toAccess(source));
        }
        return access;
    }

    /**
     * Nodes that hold a copy in a collection carrying one of these acl ids.
     * <p>
     * Uses the same query the workspace index uses to keep its own collection replicas in sync, so
     * both react to exactly the same set of nodes.
     */
    public Set<String> findNodesWithCollectionAcl(Collection<Long> aclIds, int maxNodes) throws IOException {
        Set<String> nodeIds = new LinkedHashSet<>();
        for (Long aclId : aclIds) {
            nodeIds.addAll(pageThrough(InternalQueries.queryCollectionsWithAcl(aclId), maxNodes - nodeIds.size()));
            if (nodeIds.size() >= maxNodes) {
                log.warn("stopping at {} nodes for collection acl refresh - the remaining chunks keep "
                        + "their previous access until the node is touched again", maxNodes);
                break;
            }
        }
        return nodeIds;
    }

    private Set<String> pageThrough(Query query, int limit) throws IOException {
        Set<String> nodeIds = new LinkedHashSet<>();
        List<FieldValue> after = null;
        while (nodeIds.size() < limit) {
            final List<FieldValue> searchAfter = after;
            int size = Math.min(PAGE_SIZE, limit - nodeIds.size());
            SearchResponse<Map> response = client.search(req -> {
                req.index(workspaceIndex)
                        .size(size)
                        .query(query)
                        .sort(so -> so.field(f -> f.field(PAGE_SORT_FIELD).order(SortOrder.Asc)))
                        .source(s -> s.fetch(false));
                if (searchAfter != null) {
                    req.searchAfter(searchAfter);
                }
                return req;
            }, Map.class);

            List<Hit<Map>> hits = response.hits().hits();
            if (hits.isEmpty()) {
                break;
            }
            hits.forEach(hit -> nodeIds.add(hit.id()));
            after = hits.get(hits.size() - 1).sort();
        }
        return nodeIds;
    }

    /**
     * Splits the workspace document into the inputs of the read rule.
     * <p>
     * The two collection relations are kept apart on purpose. A {@code ccm:usage} grants sight to
     * whoever may read the collection; a {@code ccm:collection_proposal} only to its coordinators.
     * Merging them - as an earlier version did - would have shown every proposal to everyone allowed
     * to read the collection.
     */
    @SuppressWarnings("unchecked")
    private static RagChunkAccess toAccess(Map<String, Object> source) {
        Set<String> collectionReaders = new LinkedHashSet<>();
        Set<String> proposalCoordinators = new LinkedHashSet<>();
        Set<String> collectionOwners = new LinkedHashSet<>();
        List<String> collections = new ArrayList<>();

        Object collectionsValue = source.get("collections");
        if (collectionsValue instanceof List<?> list) {
            for (Object element : list) {
                if (!(element instanceof Map)) {
                    continue;
                }
                Map<String, Object> collection = (Map<String, Object>) element;
                String relation = relationTypeOf(collection);
                if (RELATION_USAGE.equals(relation)) {
                    collectionReaders.addAll(authoritiesOf(collection, "read"));
                } else if (RELATION_PROPOSAL.equals(relation)) {
                    proposalCoordinators.addAll(authoritiesOf(collection, "Coordinator"));
                } else {
                    // an unknown relation grants nothing rather than everything
                    continue;
                }
                Object owner = collection.get("owner");
                if (owner != null) {
                    collectionOwners.add(owner.toString());
                }
                String uuid = uuidOf(collection);
                if (uuid != null) {
                    collections.add(uuid);
                }
            }
        }

        return new RagChunkAccess(
                readOf(source),
                List.copyOf(collectionReaders),
                List.copyOf(proposalCoordinators),
                List.copyOf(collectionOwners),
                List.copyOf(collections),
                isRestricted(source),
                grantsReadAll(source));
    }

    /** {@code ccm:restricted_access} - a node that carries it does not inherit collection rights. */
    private static boolean isRestricted(Map<String, Object> source) {
        return property(source, "ccm:restricted_access").stream().anyMatch("true"::equalsIgnoreCase);
    }

    /** {@code ReadAll} lifts the restriction again. */
    private static boolean grantsReadAll(Map<String, Object> source) {
        return property(source, "ccm:restricted_access_permissions").contains(PERMISSION_READ_ALL);
    }

    @SuppressWarnings("unchecked")
    private static List<String> property(Map<String, Object> source, String name) {
        Object properties = source.get("properties");
        if (!(properties instanceof Map)) {
            return List.of();
        }
        Object value = ((Map<String, Object>) properties).get(name);
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
        }
        return value == null ? List.of() : List.of(value.toString());
    }

    @SuppressWarnings("unchecked")
    private static String relationTypeOf(Map<String, Object> collection) {
        Object relation = collection.get("relation");
        if (!(relation instanceof Map)) {
            return null;
        }
        Object type = ((Map<String, Object>) relation).get("type");
        return type == null ? null : type.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> authoritiesOf(Map<String, Object> source, String permission) {
        Object permissions = source.get("permissions");
        if (!(permissions instanceof Map)) {
            return List.of();
        }
        Object value = ((Map<String, Object>) permissions).get(permission);
        if (value instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
        }
        return value == null ? List.of() : List.of(value.toString());
    }

    /** The node's own read authorities. */
    @SuppressWarnings("unchecked")
    private static List<String> readOf(Map<String, Object> source) {
        Object permissions = source.get("permissions");
        if (!(permissions instanceof Map)) {
            return List.of();
        }
        Object read = ((Map<String, Object>) permissions).get("read");
        if (read instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
        }
        return read == null ? List.of() : List.of(read.toString());
    }

    @SuppressWarnings("unchecked")
    private static String uuidOf(Map<String, Object> source) {
        Object nodeRef = source.get("nodeRef");
        if (!(nodeRef instanceof Map)) {
            return null;
        }
        Object id = ((Map<String, Object>) nodeRef).get("id");
        return id == null ? null : id.toString();
    }
}
