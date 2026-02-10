package org.edu_sharing.elasticsearch.alfresco.client;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class GetNodeParam {
    private List<Long> txnIds = new ArrayList<>();
}
