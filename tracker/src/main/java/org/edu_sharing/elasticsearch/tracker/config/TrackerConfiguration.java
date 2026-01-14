package org.edu_sharing.elasticsearch.tracker.config;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.elasticsearch.core.AdminService;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceFactory;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.AppInfo;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.metric.MetricContextHolder;
import org.edu_sharing.elasticsearch.tracker.TrackerServiceFactory;
import org.edu_sharing.elasticsearch.tracker.TransactionTrackerBase;
import org.edu_sharing.elasticsearch.tracker.strategy.FixNumberOfTransactionStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
@Configuration
public class TrackerConfiguration {

    private final TrackerServiceFactory trackerServiceFactory;

    private final StatusIndexServiceFactory statusIndexServiceFactory;

    private final Map<String,TrackerProvider> trackerProviders;

    private final TrackerProperties props;

    private final AdminService adminService;

    private final StatusIndexService<AppInfo> appInfoStatusService;

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

            TransactionTrackerBase trackerService = trackerServiceFactory.createTrackerService(trackerProvider::create, gnericTransactionStateService, new FixNumberOfTransactionStrategy(),metricContext);

            trackerService.setNumberOfTransactions(cfg.getTransactions());
            if(cfg.getIncludeNodeTypes() != null && !cfg.getIncludeNodeTypes().isEmpty()){
                trackerService.setIncludeNodeTypes(Arrays.asList(cfg.getIncludeNodeTypes().split(",")));
            }
            if(cfg.getExcludeNodeTypes() != null && !cfg.getExcludeNodeTypes().isEmpty()){
                trackerService.setExcludeNodeTypes(Arrays.asList(cfg.getExcludeNodeTypes().split(",")));
            }
            if(cfg.getTimeStep() != null){
                trackerService.setTimeStep(cfg.getTimeStep().toMillis());
            }

            registry.put(key, trackerService);
        });
        return registry;
    }
}
