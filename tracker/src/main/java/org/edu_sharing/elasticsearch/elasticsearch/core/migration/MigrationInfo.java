package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import lombok.Value;

@Value
public class MigrationInfo {
    /**
     * Version should be unique name
     * It will be appended on workspace and transactions index as a postfix
     * It's also used identifier for the migration status index
     */
    String version;


    /**
     * Represents the migration information specific to the "workspace" index.
     * This contains metadata and flags related to the reindexing process for the workspace index.
     * It plays a role during migration processes by indicating if a reindexing step is required
     * for the corresponding index.
     */
    IndexMigrationInfo workspace;

    /**
     * Represents the migration information specific to the "authorities" index.
     * This variable contains metadata and flags related to the reindexing process
     * for the authorities index. It is used during migration processes to determine
     * if a reindexing step is required for the corresponding index.
     */
    IndexMigrationInfo authorities;
}
