package org.edu_sharing.elasticsearch.elasticsearch.core;

import java.io.IOException;

public interface StatusIndexServiceInterface<TDATA> {
    TDATA getState() throws IOException;
    void setState(TDATA state) throws IOException;
}
