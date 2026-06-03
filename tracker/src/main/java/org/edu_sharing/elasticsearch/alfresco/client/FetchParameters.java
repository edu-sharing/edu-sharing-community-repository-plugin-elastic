package org.edu_sharing.elasticsearch.alfresco.client;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FetchParameters {
    boolean children = true;
    public static FetchParameters MINIMAL = new FetchParameters(false);
    public static FetchParameters ALL = new FetchParameters(true);
}
