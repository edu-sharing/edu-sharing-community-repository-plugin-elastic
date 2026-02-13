package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceFactory;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.tracker.config.TrackerProperties;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class MigrationCallback10_1 implements MigrationCallback {

    private final StatusIndexServiceFactory statusIndexServiceFactory;
    private final TrackerProperties props;

    @Override
    public String getName() {
        return "MigrationCallback10_1";
    }

    @Override
    public void onMigrationCallback(MigrationJob migrationJob, MigrationState migrationState, ElasticsearchClient client) {
        try {
            migrationJob.setStateMigrationCallback(migrationState,this,"started","Migration "+getName()+" started");


            for (Map.Entry<String, TrackerProperties.TrackerConfig> entry : props.getTracker().entrySet()) {
                String key  = entry.getKey();
                TrackerProperties.TrackerConfig trackerConfig = entry.getValue();
                String latestVersion = migrationJob.getVersion();
                // should be there through former copy index migration step
                String indexName = "transactions_" + latestVersion;

                StatusIndexService<Tx> currentStateService = statusIndexServiceFactory.createTransactionStateService(indexName, key);
                //main tracker formerly used 1 as document id
                StatusIndexService<Tx> oldMainTrackerStateService = statusIndexServiceFactory.createTransactionStateService(indexName, "1");
                StatusIndexService<Tx> newMainTrackerStateService = statusIndexServiceFactory.createTransactionStateService(indexName, "main");
                List<String> trackerDependencies = trackerConfig.getTrackerDependency();
                Tx dependendTx = null;
                if(trackerDependencies != null && !trackerDependencies.isEmpty()){
                    dependendTx = trackerDependencies.stream()
                            .map(d -> statusIndexServiceFactory.createTransactionStateService(indexName, d))
                            .map(s -> {
                                try {
                                    return s.getState();
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            })
                            .min(Comparator.comparingLong(Tx::getTxnCommitTime)).orElseThrow(() -> new RuntimeException("no dependend tx found"));
                }


                Tx copyFromTx = null;
                if(key.equals("main")){
                    copyFromTx = oldMainTrackerStateService.getState();
                }else if(dependendTx != null){
                    //dependend tracker should be already processed (application.properties order)
                    copyFromTx = dependendTx;
                } else if (key.equals("authorities")) {
                    //main tracker should be already processed (application.properties order)
                    copyFromTx = newMainTrackerStateService.getState();
                }else {
                    // unknown tracker with no dependency
                    continue;
                }


                Tx state = currentStateService.getState();
                if(state == null || state.getTxnCommitTime() == 0L ){
                    if(copyFromTx != null){
                        state = new Tx();
                        state.setTxnCommitTime(copyFromTx.getTxnCommitTime());
                        state.setTxnId(copyFromTx.getTxnId());
                        log.info("init tracker {} by using state from dependent or main tracker. state: {} ",key,state);
                        currentStateService.setState(state);
                    }
                }
            }
            migrationJob.setStateMigrationCallback(migrationState,this,"finished","Migration "+getName()+" finished");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
