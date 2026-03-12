package org.edu_sharing.elasticsearch.edu_sharing.api.preview;

import lombok.Data;

@Data
public class PreviewData {
    private String mimetype;
    private byte[] data;
    private boolean isIcon;
    private String type;
}
