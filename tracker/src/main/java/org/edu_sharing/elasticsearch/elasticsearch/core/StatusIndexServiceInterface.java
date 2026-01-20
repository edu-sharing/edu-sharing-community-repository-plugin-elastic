package org.edu_sharing.elasticsearch.elasticsearch.core;

import java.io.IOException;

public interface StatusIndexServiceInterface<TDATA> {
    public TDATA getState() throws IOException;
    public void setState(TDATA state) throws IOException;
}
