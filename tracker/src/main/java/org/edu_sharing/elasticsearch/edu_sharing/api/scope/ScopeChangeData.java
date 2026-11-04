package org.edu_sharing.elasticsearch.edu_sharing.api.scope;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * entry of the scope change feed (repository: ScopeChange)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScopeChangeData {
    public enum Type {
        UPSERT,
        DELETE,
        SCOPE_DELETED
    }

    private long seq;
    /** null if all scopes of the nodes are affected (node purged) */
    private String scopeId;
    private List<String> nodeIds;
    private Type type;
    private OffsetDateTime at;
}
