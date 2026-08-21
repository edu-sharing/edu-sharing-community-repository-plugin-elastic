package org.edu_sharing.elasticsearch.alfresco.client;

import lombok.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeData {
    private NodeMetadata nodeMetadata;
    private NodePreview nodePreview;
    private Reader reader;
    private String fullText;
    private boolean refreshPath = false;
    private Map<String,List<String>> permissions;
    private List<NodeData> children = new ArrayList<>();
    private Map<String, Map<String, List<String>>> valueSpaces = new HashMap<>();
    private Map<String, Map<?, ?>> extendedData = new HashMap<>();
    private Map<String, Map<?, ?>> flattenedData = new HashMap<>();

    public void setAccessControlList(AccessControlList accessControlList) {
        permissions = new HashMap<>();
        for(AccessControlEntry ace : accessControlList.getAces()){
            List<String> authorities = permissions.get(ace.permission);
            if(authorities == null){
                authorities = new ArrayList<>();
            }
            if(!authorities.contains(ace.getAuthority())){
                authorities.add(ace.getAuthority());
            }
            permissions.put(ace.getPermission(),authorities);
        }
    }

}
