package org.edu_sharing.elasticsearch.elasticsearch.core.types;

import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;

import java.util.Collections;
import java.util.List;

/**
 * Represents a configuration for filtering operations performed on node metadata.
 * The filter is based on the presence of specific aspects associated with nodes.
 * <p>
 * The configuration contains a list of aspects, `hasAspects`, which defines the required
 * aspects a node must have to match the filter. If the list is empty, all nodes will match.
 */
public record FilterConfig(
        List<String> hasAspects
) {

    public FilterConfig {
        if (hasAspects == null) hasAspects = Collections.emptyList();
    }

    public boolean match(NodeMetadata data) {
        if (hasAspects.isEmpty()) {
            return true;
        }

        return data.getAspects().containsAll(hasAspects);
    }
}
