package org.edu_sharing.elasticsearch.edu_sharing.api.scope;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * scope fields of a node, computed by the repository (ScopeIndexData) and written as-is
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScopeIndexFragment {
    private String nodeId;
    private List<String> scopeIds;
    private List<String> scopeIdsPublished;
    private List<String> scopeIdsExcluded;
    private List<String> scopeOverrides;
    private List<Map<String, Object>> scopes;

    /**
     * @return the fields of the workspace document
     */
    public Map<String, Object> toFields() {
        return Map.of(
                "scope_ids", scopeIds == null ? List.of() : scopeIds,
                "scope_ids_published", scopeIdsPublished == null ? List.of() : scopeIdsPublished,
                "scope_ids_excluded", scopeIdsExcluded == null ? List.of() : scopeIdsExcluded,
                "scope_overrides", scopeOverrides == null ? List.of() : scopeOverrides,
                "scopes", scopes == null ? List.of() : scopes);
    }
}
