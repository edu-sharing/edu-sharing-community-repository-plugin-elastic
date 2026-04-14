package org.edu_sharing.elasticsearch.tracker;

import lombok.Getter;
import org.edu_sharing.elasticsearch.alfresco.client.Transactions;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.edu_sharing.elasticsearch.tracker.main.MainTracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Getter
public class MainTrackerTest extends MainTracker {
    List<Long> allTransactionIds = new ArrayList<>();

    public MainTrackerTest() {
        super(new AlfTransactionTrackerProperties());
    }

    @Override
    protected Double calcProgress(Transactions transactions, List<Long> transactionIds) {
        if(this.allTransactionIds.containsAll(transactionIds)) {
            List<Long> list = transactionIds.stream().filter(id -> allTransactionIds.contains(id)).toList();
            throw new RuntimeException("ids already there:"+ Arrays.toString(list.toArray()));
        }
        this.allTransactionIds.addAll(transactionIds);

        return super.calcProgress(transactions, transactionIds);
    }

}
