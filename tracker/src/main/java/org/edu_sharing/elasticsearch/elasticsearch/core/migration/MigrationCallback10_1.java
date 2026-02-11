package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.tracker.TransactionTracker;
import org.edu_sharing.elasticsearch.tracker.config.TrackerRegistry;

import java.io.IOException;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class MigrationCallback10_1 implements MigrationCallback {

    private final TrackerRegistry trackerRegistry;

    @Override
    public String getName() {
        return "MigrationCallback10_1";
    }

    @Override
    public void onMigrationCallback(MigrationJob migrationJob, MigrationState migrationState, ElasticsearchClient client) {
        try {
            migrationJob.setStateMigrationCallback(migrationState,this,"started","Migration "+getName()+" started");
            for(Map.Entry<String, TransactionTracker> entry : trackerRegistry.getRegisteredTracker().entrySet()){
                String key  = entry.getKey();
                TransactionTracker trackerService = entry.getValue();

                Tx state = trackerService.getTransactionStateService().getState();

                // TODO should be hard wired
//                if(state == null || state.getTxnCommitTime() == 0L ){
//                    Tx copyFromState;
//                    if(trackerService.getTrackerStrategy() instanceof DependentStatusIndexServiceStrategie s){
//                        copyFromState = s.getTransactionStateService().getState();
//                    } else {
//                        copyFromState = ((DefaultTransactionTracker) transactionTracker).getTransactionStateService().getState();
//                    }
//                    if(copyFromState != null){
//                        log.info("init tracker {} by using state from dependent or main tracker",key);
//                        state = new Tx();
//                        state.setTxnCommitTime(copyFromState.getTxnCommitTime());
//                        state.setTxnId(copyFromState.getTxnId());
//                        trackerService.getTransactionStateService().setState(state);
//                    }
//                }
            }
            migrationJob.setStateMigrationCallback(migrationState,this,"finished","Migration "+getName()+" finished");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
