package org.edu_sharing.elasticsearch.elasticsearch.utils.utils;

import lombok.Getter;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;

import java.util.Map;

@Getter
public class NodeMetadataSimple {
    private Long id;
    private String nodeRef;
    private String type;
    public NodeMetadataSimple(NodeMetadata nodeMetadata) {
        id = nodeMetadata.getId();
        nodeRef = nodeMetadata.getNodeRef();
        type = nodeMetadata.getType();
    }

    public NodeMetadataSimple(Map source) {
        if(source.get("dbid") instanceof Integer) {
            id = Long.valueOf((Integer) source.get("dbid"));
        } else {
            id = (Long) source.get("dbid");
        }
        Map ref = (Map) source.get("nodeRef");
        Map storeRef = (Map) ref.get("storeRef");
        nodeRef = storeRef.get("protocol")+"://"+storeRef.get("identifier")+"/"+ref.get("id");
        type = (String) source.get("type");
    }
}