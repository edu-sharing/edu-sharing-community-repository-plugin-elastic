package org.edu_sharing.elasticsearch.alfresco.client;

import lombok.Data;

@Data
public class NodePreview {
    private String mimetype;
    private byte[] small;
    private byte[] large;
    private boolean isIcon = true;
    private String type = "TYPE_DEFAULT";
}
