package org.edu_sharing.elasticsearch.tracker;

import org.edu_sharing.elasticsearch.alfresco.client.Transactions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DefaultTransactionTrackerTest extends DefaultTransactionTracker {
    List<Long> allTransactionIds = new ArrayList<>();

    @Override
    protected Double calcProgress(Transactions transactions, List<Long> transactionIds) {
        if(this.allTransactionIds.containsAll(transactionIds)) {
            List<Long> list = transactionIds.stream().filter(id -> allTransactionIds.contains(id)).toList();
            throw new RuntimeException("ids already there:"+ Arrays.toString(list.toArray()));
        }
        this.allTransactionIds.addAll(transactionIds);

        return super.calcProgress(transactions, transactionIds);
    }

    public List<Long> getAllTransactionIds() {
        return allTransactionIds;
    }
}
