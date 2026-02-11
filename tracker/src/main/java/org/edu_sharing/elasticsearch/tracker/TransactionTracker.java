package org.edu_sharing.elasticsearch.tracker;

import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceInterface;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;

public interface TransactionTracker {
    State track();

    StatusIndexServiceInterface<Tx> getTransactionStateService();

    java.util.List<String> getIncludeNodeTypes();

    java.util.List<String> getExcludeNodeTypes();

    void setNumberOfTransactions(int numberOfTransactions);

    void setIncludeNodeTypes(java.util.List<String> includeNodeTypes);

    void setExcludeNodeTypes(java.util.List<String> excludeNodeTypes);

    void setExcludeAspects(java.util.List<String> excludeAspects);

    void setIncludeAspects(java.util.List<String> includeAspects);

    void setStoreProtocol(String storeProtocol);

    void setStoreIdentifier(String storeIdentifier);

    long getTimeStep();

    void setTimeStep(long timeStep);

    enum State {
        INPROGRESS,
        FINISHED,
        EXCEPTION
    }
}
