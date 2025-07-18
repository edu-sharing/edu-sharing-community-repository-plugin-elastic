package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import co.elastic.clients.elasticsearch._types.Script;

public record IndexMigrationInfo(
        /*
         * Indicates whether the tracker should reindex all data from edu-sharing or not
         * This can be useful if new fields are added to the index
         */
        boolean requiresReindex,

        Script migrationScript


) {
}
