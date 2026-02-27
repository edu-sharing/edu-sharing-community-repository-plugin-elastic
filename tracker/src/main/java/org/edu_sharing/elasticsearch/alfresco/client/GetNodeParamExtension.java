package org.edu_sharing.elasticsearch.alfresco.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GetNodeParamExtension extends GetNodeParam {

    private List<String> includeNodeTypes = null;
    private List<String> excludeNodeTypes = null;
    private List<String> includeAspects = null;
    private List<String> excludeAspects = null;
    private String storeProtocol = null;
    private String storeIdentifier = null;
}
