package org.edu_sharing.elasticsearch.tracker.mock;

import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceInterface;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;

import java.io.IOException;

public class StatusIndexServiceMock implements StatusIndexServiceInterface<Tx> {

    Tx state;

    @Override
    public Class<Tx> getStateClass() {
        return Tx.class;
    }

    @Override
    public Tx getState(){
        return state;
    }

    @Override
    public void setState(Tx state)  {
        this.state = state;
    }

    @Override
    public void resetState() throws IOException {
        state = null;
    }
}
