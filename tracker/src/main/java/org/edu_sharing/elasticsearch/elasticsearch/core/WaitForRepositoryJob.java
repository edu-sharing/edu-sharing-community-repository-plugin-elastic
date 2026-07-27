package org.edu_sharing.elasticsearch.elasticsearch.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.edu_sharing.api.RepositoryAvailabilityProbe;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Gates tracking in the default mode on repository readiness.
 * <p>
 * Polls the repository asynchronously (so application startup and the management/health endpoints
 * are not blocked) and signals {@link ApplicationStatePublisher#markRepositoryReady()} once the
 * repository answers. This replaces the repository wait that previously lived in the container
 * entrypoint, allowing the ES-only startup work (hooks, index migration) to run while the
 * repository is still booting.
 */
@Slf4j
@RequiredArgsConstructor
public class WaitForRepositoryJob implements SmartInitializingSingleton {

    private final RepositoryAvailabilityProbe repositoryAvailabilityProbe;
    private final ApplicationStatePublisher applicationState;

    @Value("${repository.readiness.pollInterval:5s}")
    private Duration pollInterval;

    private ScheduledFuture<?> scheduledFuture;

    @Override
    public void afterSingletonsInstantiated() {
        long intervalSeconds = Math.max(1, pollInterval.toSeconds());
        scheduledFuture = Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                if (!repositoryAvailabilityProbe.isAvailable()) {
                    log.info("Waiting for repository ...");
                    return;
                }

                scheduledFuture.cancel(false);
                log.info("Repository is ready");
                applicationState.markRepositoryReady();
            } catch (Exception ex) {
                log.error(ex.getMessage(), ex);
            }
        }, 0, intervalSeconds, TimeUnit.SECONDS);
    }
}