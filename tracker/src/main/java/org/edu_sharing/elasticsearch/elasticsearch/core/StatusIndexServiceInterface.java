package org.edu_sharing.elasticsearch.elasticsearch.core;

import java.io.IOException;

public interface StatusIndexServiceInterface<TDATA> {
    Class<TDATA> getStateClass();
    TDATA getState() throws IOException;
    void setState(TDATA state) throws IOException;
    void resetState() throws IOException;
}
