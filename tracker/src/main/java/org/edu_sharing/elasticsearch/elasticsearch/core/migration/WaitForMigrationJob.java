package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.ApplicationStatePublisher;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class WaitForMigrationJob implements SmartInitializingSingleton {

    private final MigrationService migrationService;
    private final ApplicationStatePublisher applicationState;

    private ScheduledFuture<?> scheduledFuture;

    @Override
    public void afterSingletonsInstantiated() {
        try {
            if (!migrationService.requiresMigration()) {
                invokeMigrationCompleted();
                return;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        scheduledFuture = Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                if (!migrationService.checkForMigrationStatus()) {
                    log.info("Wait for migration...");
                    return;
                }

                scheduledFuture.cancel(false);
                invokeMigrationCompleted();
            } catch (Exception ex) {
                log.error(ex.getMessage(), ex);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void invokeMigrationCompleted() {
        log.info("invoke migration completed");
        applicationState.markMigrationCompleted();
    }
}
