package org.edu_sharing.elasticsearch.tracker.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkAccess;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Carries access changes into the chunk index.
 * <p>
 * Needed because none of the three events that change who may see a node is a transaction on that
 * node, so the RAG tracker never sees them:
 * <ul>
 *   <li>the node's own ACL changes - an ACL change set, which is why {@code AclTracker} exists</li>
 *   <li>a collection's ACL changes - the node is not touched at all</li>
 *   <li>a copy is added to or removed from a collection - the transaction touches the copy</li>
 * </ul>
 * All three end in the same operation: resolve the affected nodes' access afresh and write it. The
 * union is never patched, so nothing here has to know where an authority came from - see
 * {@link RagAccessResolver}.
 * <p>
 * Callers must run this <em>after</em> the workspace index reflects the change and has been
 * refreshed, since that index is what the new union is read from.
 */
@Slf4j
@RequiredArgsConstructor
public class RagAccessSync {

    /**
     * Bound on how many nodes one event may refresh. A collection can hold a very large number of
     * originals, and rewriting their chunks means rewriting vectors; a change that big is better
     * spread over several runs than allowed to stall the tracker.
     */
    private final int maxNodesPerEvent;

    private final RagChunkService ragChunkService;
    private final RagAccessResolver accessResolver;

    /**
     * Reacts to changed ACLs, both on nodes and on collections.
     * <p>
     * The two are found differently - a node by the {@code aclId} its own chunks carry, a collection
     * by the copies it holds, which only the workspace index knows about - but both converge on the
     * same recomputation.
     */
    public void onAclsChanged(Collection<Long> aclIds) throws IOException {
        if (aclIds.isEmpty()) {
            return;
        }
        Set<String> affected = new LinkedHashSet<>(
                ragChunkService.findNodeIdsByAclIds(aclIds, maxNodesPerEvent));
        affected.addAll(accessResolver.findNodesWithCollectionAcl(aclIds, maxNodesPerEvent));
        refresh(affected);
    }

    /** Reacts to a copy appearing in or disappearing from a collection. */
    public void onCollectionMembershipChanged(Collection<String> nodeIds) throws IOException {
        refresh(new LinkedHashSet<>(nodeIds));
    }

    /**
     * Writes the current access of every given node, skipping the ones the chunk index does not hold
     * and the ones whose union has not actually changed.
     */
    private void refresh(Set<String> nodeIds) throws IOException {
        if (nodeIds.isEmpty()) {
            return;
        }
        Map<String, RagChunkService.ChunkState> indexed =
                ragChunkService.findIndexState(nodeIds);
        if (indexed.isEmpty()) {
            return;
        }
        Map<String, RagChunkAccess> resolved = accessResolver.resolve(indexed.keySet());

        List<RagChunkService.AccessUpdate> updates = new ArrayList<>(resolved.size());
        for (Map.Entry<String, RagChunkAccess> entry : resolved.entrySet()) {
            RagChunkService.ChunkState state = indexed.get(entry.getKey());
            if (state != null) {
                updates.add(new RagChunkService.AccessUpdate(
                        entry.getKey(), state.chunkCount(), entry.getValue()));
            }
        }
        Set<String> failed = ragChunkService.updateAccess(updates);
        log.info("rag access: requested={} indexed={} written={} failed={}",
                nodeIds.size(), indexed.size(), updates.size(), failed.size());
    }
}
