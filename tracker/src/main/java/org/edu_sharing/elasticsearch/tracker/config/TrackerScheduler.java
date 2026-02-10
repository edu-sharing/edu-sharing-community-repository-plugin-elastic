package org.edu_sharing.elasticsearch.tracker.config;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationCompletedAware;
import org.edu_sharing.elasticsearch.tracker.TransactionTracker;
import org.edu_sharing.elasticsearch.tracker.TransactionTrackerBase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class TrackerScheduler implements MigrationCompletedAware {

    private final Map<String, TransactionTrackerBase> trackerRegistry;
    private final TrackerProperties props;

    private final List<ScheduledExecutorService> executors = new ArrayList<>();

    @Value("${shutdown.on.exception}")
    boolean shutDownOnException = true;

    @Setter
    private ApplicationContext applicationContext;

    private boolean migrated = false;

    @PostConstruct
    public void start() {
        trackerRegistry.forEach((key, tracker) -> {
            ScheduledExecutorService executor =
                    Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r);
                        t.setName(key); // Threadname aus Config-Key
                        t.setDaemon(true);
                        return t;
                    });

            executors.add(executor);

            long intervall = props.getTracker().get(key).getInterval();

            executor.scheduleWithFixedDelay(
                    () -> TrackerScheduler.this.track(tracker),
                    0,
                    intervall,
                    TimeUnit.MILLISECONDS
            );
        });
    }
    private void track(TransactionTrackerBase transactionTracker) {
        if(!migrated){
            return;
        }
        boolean transactionChanges;
        do {
            transactionChanges = false;
            try {
                transactionChanges = (transactionTracker.track() == TransactionTracker.State.INPROGRESS);
                log.info("recursive transactionChanges: {}", transactionChanges);
            }catch (Throwable e){
                log.error(e.getMessage(),e);
                if((e instanceof OutOfMemoryError) && shutDownOnException){
                    log.info("will shutdown tracker cause of exception: {}", e.getMessage(), e);
                    ((ConfigurableApplicationContext) applicationContext).close();
                }
                if((e instanceof ElasticsearchException)) {
                    if(((ElasticsearchException)e).error() != null) {
                        log.error(((ElasticsearchException)e).error().toString(),e);
                    }
                }
            }
        } while (transactionChanges);
    }

    @PreDestroy
    public void shutdown() {
        executors.forEach(ExecutorService::shutdown);
    }

    @Override
    public void MigrationCompleted() {
        migrated = true;
    }
}

