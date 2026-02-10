package org.edu_sharing.elasticsearch.tracker.config;

import lombok.RequiredArgsConstructor;
import org.apache.cxf.common.util.StringUtils;
import org.edu_sharing.elasticsearch.elasticsearch.core.*;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.AppInfo;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.metric.MetricContextHolder;
import org.edu_sharing.elasticsearch.tracker.DefaultTransactionTracker;
import org.edu_sharing.elasticsearch.tracker.TrackerServiceFactory;
import org.edu_sharing.elasticsearch.tracker.TransactionTracker;
import org.edu_sharing.elasticsearch.tracker.TransactionTrackerBase;
import org.edu_sharing.elasticsearch.tracker.strategy.FixNumberOfTransactionStrategy;
import org.edu_sharing.elasticsearch.tracker.strategy.StatusIndexServiceStrategie;
import org.edu_sharing.elasticsearch.tracker.strategy.TrackerStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class TrackerConfiguration {

    private final TrackerServiceFactory trackerServiceFactory;
    private final StatusIndexServiceFactory statusIndexServiceFactory;
    private final Map<String,TrackerProvider> trackerProviders;
    private final TrackerProperties props;
    private final AdminService adminService;
    private final StatusIndexService<AppInfo> appInfoStatusService;
    private final TransactionTracker transactionTracker;

    @Bean
    Map<String, TransactionTrackerBase> trackerRegistry() throws IOException {
        String appVersion = appInfoStatusService.getState().getTrackerVersion();
        Map<String, TransactionTrackerBase> registry = new HashMap<>();
        props.getTracker().forEach((key, cfg) -> {
            TrackerProvider trackerProvider = trackerProviders.get(cfg.getProvider());
            if(trackerProvider == null){
                throw new RuntimeException("No tracker provider for key: " + cfg.getProvider());
            }

            String indexName = "transactions_" + key + "_" + appVersion;
            IndexConfiguration indexConfiguration = new IndexConfiguration(req -> req.index(indexName));
            try {
                adminService.createIndex(indexConfiguration);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            StatusIndexService<Tx> gnericTransactionStateService = statusIndexServiceFactory.createTransactionStateService(indexConfiguration.getIndex());

            MetricContextHolder.MetricContext metricContext = MetricContextHolder.MetricContext.builder()
                    .labelProgress(key+"Progress")
                    .descriptionProgress(key.toUpperCase()+"progress")
                    .labelDelay(key+"Delay")
                    .descriptionDelay(key.toUpperCase()+" Delay in seconds").build();
            TrackerStrategy strategy = new FixNumberOfTransactionStrategy();
            if(!StringUtils.isEmpty(cfg.getTrackerDependency())){
                StatusIndexServiceInterface<Tx> transactionStateService;
                if(cfg.getTrackerDependency().equals("main")){
                    transactionStateService = ((DefaultTransactionTracker) transactionTracker).getTransactionStateService();
                }else{
                    TransactionTrackerBase transactionTrackerBase = registry.get(cfg.getTrackerDependency());
                    if(transactionTrackerBase == null) throw new RuntimeException("tracker " + cfg.getTrackerDependency() + " not found in registry. please add "+key+" in config after "+cfg.getTrackerDependency());
                    transactionStateService = transactionTrackerBase.getTransactionStateService();
                }
                strategy = new StatusIndexServiceStrategie(transactionStateService);
            }

            TransactionTrackerBase trackerService = trackerServiceFactory.createTrackerService(trackerProvider::create, gnericTransactionStateService, strategy, metricContext);

            trackerService.setNumberOfTransactions(cfg.getTransactions());
            if(cfg.getIncludeNodeTypes() != null && !cfg.getIncludeNodeTypes().isEmpty()){
                trackerService.setIncludeNodeTypes(Arrays.asList(cfg.getIncludeNodeTypes().split(",")));
            }
            if(cfg.getExcludeNodeTypes() != null && !cfg.getExcludeNodeTypes().isEmpty()){
                trackerService.setExcludeNodeTypes(Arrays.asList(cfg.getExcludeNodeTypes().split(",")));
            }
            if(cfg.getIncludeAspects() != null && !cfg.getIncludeAspects().isEmpty()){
                trackerService.setIncludeAspects(Arrays.asList(cfg.getIncludeAspects().split(",")));
            }
            if(cfg.getExcludeAspects() != null && !cfg.getExcludeAspects().isEmpty()){
                trackerService.setExcludeAspects(Arrays.asList(cfg.getExcludeAspects().split(",")));
            }
            if(cfg.getTimeStep() != null){
                trackerService.setTimeStep(cfg.getTimeStep().toMillis());
            }
            if(cfg.getStoreIdentifier() != null && !cfg.getStoreIdentifier().isEmpty()){
                trackerService.setStoreIdentifier(cfg.getStoreIdentifier());
            }
            if(cfg.getStoreProtocol() != null && !cfg.getStoreProtocol().isEmpty()){
                trackerService.setStoreProtocol(cfg.getStoreProtocol());
            }

            registry.put(key, trackerService);
        });
        return registry;
    }
}
