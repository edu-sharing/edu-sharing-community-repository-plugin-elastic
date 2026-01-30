package org.edu_sharing.elasticsearch.tracker.mock;

import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceInterface;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;

public class StatusIndexServiceMock implements StatusIndexServiceInterface<Tx> {

    Tx state;

    @Override
    public Tx getState(){
        return state;
    }

    @Override
    public void setState(Tx state)  {
        this.state = state;
    }
}
