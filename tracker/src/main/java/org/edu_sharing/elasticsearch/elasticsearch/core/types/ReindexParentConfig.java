package org.edu_sharing.elasticsearch.elasticsearch.core.types;


/**
 * Represents the configuration for reindexing parent elements associated with a specific type.
 * This configuration is used to control the behavior of the reindexing process, including whether
 * reindexing is enabled, how far to look ahead when processing parents, and any filtering criteria
 * that should be applied during the reindexing operation.
 * <p>
 * The following properties define the configuration:
 * <p>
 * - `enabled`: Indicates whether the parent reindexing process is enabled. If {@code false}, no
 *   reindexing will occur.
 * - `maxLookAHead`: Specifies the maximum number of parent elements to consider when performing
 *   the reindexing process. If the value is less than or equal to 0, it is automatically adjusted
 *   to a minimum of 1.
 * - `filter`: Defines the filtering criteria for selecting which parent elements should be reindexed.
 *   If no filtering criteria are specified, a default filter with no restrictions is applied.
 * <p>
 * Instances of this class use default values for null or invalid properties:
 * <p>
 * - If `maxLookAHead` is less than or equal to 0, it is set to 1.
 * - If `filter` is {@code null}, it is replaced with a default {@link FilterConfig} instance with
 *   no filter criteria.
 */
public record ReindexParentConfig(
        boolean enabled,
        int maxLookAHead,
        FilterConfig filter
) {
    public ReindexParentConfig {
        if (maxLookAHead <= 0) maxLookAHead = 1; // the min is 1
        if (filter == null) filter = new FilterConfig(null);
    }
}
