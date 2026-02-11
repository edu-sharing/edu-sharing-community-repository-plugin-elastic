package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.tracker.DefaultTransactionTracker;
import org.edu_sharing.elasticsearch.tracker.TransactionTracker;
import org.edu_sharing.elasticsearch.tracker.TransactionTrackerBase;
import org.edu_sharing.elasticsearch.tracker.strategy.StatusIndexServiceStrategie;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Date;
import java.util.Map;

@Slf4j
public class MigrationCallback10_1 implements MigrationCallback {


    @Override
    public String getName() {
        return "MigrationCallback10_1";
    }

    @Override
    public void onMigrationCallback(MigrationJob migrationJob, MigrationState migrationState, ElasticsearchClient client, Map<String, TransactionTrackerBase> trackerRegistry, TransactionTracker transactionTracker) {
        try {
            migrationJob.setStateMigrationCallback(migrationState,this,"started","Migration "+getName()+" started");
            for(Map.Entry<String, TransactionTrackerBase> entry : trackerRegistry.entrySet()){
                String key  = entry.getKey();
                TransactionTrackerBase trackerService = entry.getValue();
                Tx state = trackerService.getTransactionStateService().getState();
                if(state == null || state.getTxnCommitTime() == 0L ){
                    Tx copyFromState = null;
                    if(trackerService.getTrackerStrategy() instanceof StatusIndexServiceStrategie s){
                        copyFromState = s.getTransactionStateService().getState();
                    }else{
                        copyFromState = ((DefaultTransactionTracker) transactionTracker).getTransactionStateService().getState();
                    }
                    if(copyFromState != null){
                        log.info("init tracker {} by using state from dependent or main tracker",key);
                        state = new Tx();
                        state.setTxnCommitTime(copyFromState.getTxnCommitTime());
                        state.setTxnId(copyFromState.getTxnId());
                        trackerService.getTransactionStateService().setState(state);
                    }
                }
            }
            migrationJob.setStateMigrationCallback(migrationState,this,"finished","Migration "+getName()+" finished");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
