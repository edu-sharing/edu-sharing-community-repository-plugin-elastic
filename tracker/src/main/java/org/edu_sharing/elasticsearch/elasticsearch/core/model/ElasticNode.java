package org.edu_sharing.elasticsearch.elasticsearch.core.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class ElasticNode {
    private Long aclId;
    private String type;
    private String owner;
    private String fulldisplaypath;
    private List<String> fullpaths;
    private List<String> path;

    List<String> aspects;
    Map<String, Object> properties;
    private Long txnId;
    private NodeRef nodeRef;
    private NodeRef parentRef;

    @Data
    public static class NodeRef{
        String id;
        StoreRef storeRef;
    }

    @Data
    public static class StoreRef{
        String identifier,protocol;
    }
}


