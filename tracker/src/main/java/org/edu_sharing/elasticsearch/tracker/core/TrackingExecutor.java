package org.edu_sharing.elasticsearch.tracker.core;


import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.ApplicationState;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;


@Slf4j
@RequiredArgsConstructor
public class TrackingExecutor<STATUS> implements ApplicationContextAware {
    private final Tracker<STATUS> tracker;
    private final TrackingContext<STATUS> context;
    private final ApplicationState applicationState;

    @Setter
    private ApplicationContext applicationContext;

    @Value("${tracker.shutdown.on.exception}")
    private boolean shutDownOnException = true;

    public void track() {
        if (!applicationState.canTrack()) {
            return;
        }

        boolean transactionChanges;
        do {
            transactionChanges = false;
            try {
                transactionChanges = (tracker.track(context) == Tracker.State.IN_PROGRESS);
                log.info("recursive transactionChanges: {}", transactionChanges);
            } catch (Throwable e) {
                log.error(e.getMessage(), e);
                if ((e instanceof OutOfMemoryError) && shutDownOnException) {
                    log.info("will shutdown tracker cause of exception: {}", e.getMessage(), e);
                    ((ConfigurableApplicationContext) applicationContext).close();
                }
                if ((e instanceof ElasticsearchException)) {
                    if (((ElasticsearchException) e).error() != null) {
                        log.error(((ElasticsearchException) e).error().toString(), e);
                    }
                }
            }
        } while (transactionChanges);
    }
}
