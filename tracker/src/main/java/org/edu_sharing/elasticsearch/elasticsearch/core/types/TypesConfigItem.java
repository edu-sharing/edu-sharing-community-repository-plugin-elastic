package org.edu_sharing.elasticsearch.elasticsearch.core.types;


import java.util.Collections;
import java.util.List;

/**
 * Represents a configuration item for a specific type. Contains properties that define how the type is managed
 * within Elasticsearch and its behavior in various operations such as indexing and fetching child elements.
 * This class also supports configuration for reindexing parent elements associated with the type.
 * <p>
 * The following properties define the configuration:
 * <p>
 * - `type`: The identifier for the type.
 * - `index`: Indicates if the type should be indexed in Elasticsearch.
 * - `fetchChildren`: A list of child type identifiers that should be fetched when the type is queried; defaults to an empty list if not specified. Set ALL to fetch all children.
 * - `reindexParent`: Configuration related to reindexing parent elements associated with the type. Defaults to a configuration with reindexing disabled if not provided.
 * <p>
 * Instances of this class use default values for null properties:
 * - If `fetchChildren` is null, it is replaced with an empty list.
 * - If `reindexParent` is null, it is replaced with a default {@link ReindexParentConfig} instance.
 */
public record TypesConfigItem(
        String type,
        boolean index,
        List<String> fetchChildren,
        ReindexParentConfig reindexParent) {

    public TypesConfigItem {
        if (fetchChildren == null) fetchChildren = Collections.emptyList();
        if (reindexParent == null) reindexParent = new ReindexParentConfig(false, 0, null);
    }
}
