package org.edu_sharing.elasticsearch.alfresco.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeData {
    @Setter
    NodeMetadata nodeMetadata;
    @Setter
    NodePreview nodePreview;
    @Setter
    Reader reader;
    @Setter
    String fullText;

    Map<String,List<String>> permissions;

    @Setter
    boolean refreshPath = false;

    List<NodeData> children = new ArrayList<>();

    @Setter
    Map<String, Map<String, List<String>>> valueSpaces = new HashMap<>();

    public NodeMetadata getNodeMetadata() {
        return nodeMetadata;
    }

    public boolean isRefreshPath() {
        return refreshPath;
    }

    public NodePreview getNodePreview() {
        return nodePreview;
    }

    public Reader getReader() {
        return reader;
    }

    public Map<String, Map<String, List<String>>> getValueSpaces() {
        return valueSpaces;
    }

    public String getFullText() {
        return fullText;
    }

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

    public Map<String,List<String>> getPermissions(){
        return permissions;
    }

    public List<NodeData> getChildren() {
        return children;
    }
}
