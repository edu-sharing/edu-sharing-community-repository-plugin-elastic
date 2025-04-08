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
     * Indicates whether the tracker should reindex all data from edu-sharing or not
     * This can be useful if new fields are added to the index
     */
    boolean requiresReindex;

    /**
     * Indicates wether the tracker should reindex all authorities in a separate step before
     * document reindex (that would also reindex authorities)
     */
    boolean requiresAuthoritiesReindex;

}
