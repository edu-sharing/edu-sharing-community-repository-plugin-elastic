package org.edu_sharing.elasticsearch.alfresco.client;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

public class GetNodeParamExtension extends GetNodeParam {

    @Getter
    @Setter
    List<String> includeNodeTypes = new ArrayList<>();

    public GetNodeParamExtension(){

    }

}
