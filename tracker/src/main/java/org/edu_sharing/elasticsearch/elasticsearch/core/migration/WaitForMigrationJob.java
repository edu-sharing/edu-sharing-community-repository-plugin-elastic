package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RequiredArgsConstructor
public class WaitForMigrationJob implements ApplicationContextAware, SmartInitializingSingleton {

    private final MigrationService migrationService;

    @Setter
    private ApplicationContext applicationContext;
    private ScheduledFuture<?> scheduledFuture;


    private final AtomicBoolean migrationCompleted = new AtomicBoolean(false);

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
        migrationCompleted.set(true);
        Map<String, MigrationCompletedAware> results = applicationContext.getBeansOfType(MigrationCompletedAware.class, false, false);
        for (MigrationCompletedAware invoker : results.values()) {
            invoker.migrationCompleted();
        }
    }

    public boolean isMigrationCompleted() {
        return migrationCompleted.get();
    }
}
