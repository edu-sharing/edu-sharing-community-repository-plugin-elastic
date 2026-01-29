package org.edu_sharing.elasticsearch.tracker;

import org.edu_sharing.elasticsearch.alfresco.client.Transactions;

import java.util.ArrayList;
import java.util.List;

public class DefaultTransactionTrackerTest extends DefaultTransactionTracker {
    List<Long> allTransactionIds = new ArrayList<>();

    @Override
    protected Double calcProgress(Transactions transactions, List<Long> transactionIds) {
        this.allTransactionIds.addAll(transactionIds);

        return super.calcProgress(transactions, transactionIds);
    }

    public List<Long> getAllTransactionIds() {
        return allTransactionIds;
    }
}
