package org.edu_sharing.elasticsearch.tracker;

import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Transaction;
import org.edu_sharing.elasticsearch.alfresco.client.Transactions;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.metric.MetricContextHolder;
import org.edu_sharing.elasticsearch.tracker.core.Tracker;
import org.edu_sharing.elasticsearch.tracker.core.TrackingContext;
import org.edu_sharing.elasticsearch.tracker.mock.AlfrescoApiMock;
import org.edu_sharing.elasticsearch.tracker.mock.StatusIndexServiceMock;
import org.edu_sharing.elasticsearch.tracker.mock.TestUtil;
import org.edu_sharing.elasticsearch.tracker.strategy.MaxCommitTimeStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;

@Slf4j
@ExtendWith(MockitoExtension.class)
public class TrackerTest {

    private MaxCommitTimeStrategy strategy;

    @Mock
    private EduSharingService eduSharingService;

    @InjectMocks
    private MainTrackerTest tracker;

    @InjectMocks
    private StatusIndexServiceMock statusIndexService;

    @Test
    public void testGetSomeTransactions() throws Exception {
        Transactions data = TestUtil.loadTransactions("transactionsTest.json");
        tracker.setAlfClient(new AlfrescoApiMock(data));
        strategy = new MaxCommitTimeStrategy(1744362767224L);

        long limit = strategy.getLimit() + 1;
        long fromCommitTime = 0L;
        long timeStep = tracker.getConfig().getTimeStep().toMillis();
        int maxResult = 100;

        long currentFrom = fromCommitTime;
        List<Transaction> collected = new ArrayList<>();
        Transactions transactions;
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

    @Test
    public void testTrack() throws Exception {
        testTrack("transactionsTest.json",1744362767224L);
    }

    public void testTrack(String testData, long maxCommitTime) throws Exception {
        Transactions data = TestUtil.loadTransactions(testData);
        tracker.setAlfClient(new AlfrescoApiMock(data));
        strategy = new MaxCommitTimeStrategy(maxCommitTime);
        TrackingContext<Tx> trackingContext = new TrackingContext<>(strategy, statusIndexService, MetricContextHolder.MetricContext.builder().build());

        doNothing().when(eduSharingService).refreshValuespaceCache();
        Tracker.State state;
        do{
            state = tracker.track(trackingContext);
        }while (state == Tracker.State.IN_PROGRESS);
        assertThat(state).isNotNull();
        assertThat(state).isEqualTo(Tracker.State.FINISHED);

        long txnCommitTime = statusIndexService.getState().getTxnCommitTime();
        assertThat(txnCommitTime).isEqualTo(strategy.getLimit());

        assertThat(tracker.getAllTransactionIds())
                .containsExactlyElementsOf(
                        data.getTransactions().stream()
                                .map(Transaction::getId)
                                .toList()
                );
    }

    @Test
    public void testTrackMultipleCommitTimesForSameTx() throws Exception {
        //14277451 -> missing
        //1764250670277 duplicate commit time
        tracker.getConfig().setNumberOfTransactions(500);
        testTrack("transactionsTest2.json",1764257439907L);
    }
}
