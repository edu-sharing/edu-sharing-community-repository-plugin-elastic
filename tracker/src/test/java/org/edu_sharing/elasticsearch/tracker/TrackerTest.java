package org.edu_sharing.elasticsearch.tracker;

import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Transaction;
import org.edu_sharing.elasticsearch.alfresco.client.Transactions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class TrackerTest {
    DefaultTransactionTracker tracker;

    Transactions data;

    public TrackerTest() throws Exception {
        data = TestUtil.loadTransactions("transactionsTest.json");
        tracker = new DefaultTransactionTracker();
        tracker.setAlfClient(new AlfrescoApiMock(data));
    }
    @Test
    public void testGetSomeTransactions() {
        long limit = 1744362767224L;
        limit = limit + 1;
        long fromCommitTime = 0L;
        long timeStep = tracker.getTimeStep();
        int maxResult = 100;

        long currentFrom = fromCommitTime;
        List<Transaction> collected = new ArrayList<>();
        Transactions transactions = null;
        do{
            transactions = tracker.getSomeTransactions(currentFrom,timeStep,maxResult,limit);
            if(transactions.getTransactions().isEmpty()){
                log.info("No transactions found");
                break;
            }
            log.info("{}",transactions.getTransactions().get(transactions.getTransactions().size() -1).getCommitTimeMs());
            collected.addAll(transactions.getTransactions());
            currentFrom = transactions.getTransactions().get(transactions.getTransactions().size() - 1).getCommitTimeMs() + 1;

        }while(!transactions.getTransactions().isEmpty());

        assertThat(collected)
                .extracting(Transaction::getId)
                .containsExactlyElementsOf(
                        data.getTransactions().stream()
                                .map(Transaction::getId)
                                .toList()
                );
    }
}
