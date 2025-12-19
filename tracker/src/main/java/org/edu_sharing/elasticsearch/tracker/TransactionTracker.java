package org.edu_sharing.elasticsearch.tracker;

public interface TransactionTracker {
    State track();
    
    
    public enum State {
        INPROGRESS,
        FINISHED,
        EXCEPTION
    }
}
