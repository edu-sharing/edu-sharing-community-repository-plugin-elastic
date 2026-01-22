package org.edu_sharing.elasticsearch.alfresco.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class GetNodeParamExtension extends GetNodeParam {

    @Getter
    @Setter
    List<String> includeNodeTypes = null;

    @Getter
    @Setter
    List<String> excludeNodeTypes = null;

    @Getter
    @Setter
    List<String> includeAspects = null;

    @Getter
    @Setter
    List<String> excludeAspects = null;

    @Getter
    @Setter
    String storeProtocol = null;

    @Getter
    @Setter
    String storeIdentifier = null;


    public GetNodeParamExtension(){

    }
}
