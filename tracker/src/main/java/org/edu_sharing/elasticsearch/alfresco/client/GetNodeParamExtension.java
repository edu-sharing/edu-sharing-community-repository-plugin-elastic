package org.edu_sharing.elasticsearch.alfresco.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class GetNodeParamExtension extends GetNodeParam {

    @Getter
    @Setter
    List<String> includeNodeTypes = null;

    @Getter
    @Setter
    List<String> excludeNodeTypes = null;



    public GetNodeParamExtension(){

    }

}
