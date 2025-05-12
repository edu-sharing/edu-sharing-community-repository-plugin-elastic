package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class WaitForMigrationJob implements ApplicationContextAware {

    private final MigrationService migrationService;

    @Setter
    private ApplicationContext applicationContext;
    private ScheduledFuture<?> scheduledFuture;

    @PostConstruct
    public void checkMigrationStatus() throws IOException {
        if(!migrationService.requiresMigration()) {
            invokeMigrationCompleted();
            return;
        }

        scheduledFuture = Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                if (!migrationService.checkForMigrationStatus()) {
                    log.info("Wait for migration...");
                    return;
                }

                scheduledFuture.cancel(false);
                invokeMigrationCompleted();
            } catch (Exception ex){
                log.error(ex.getMessage(), ex);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void invokeMigrationCompleted() {
        Map<String, MigrationCompletedAware> results = applicationContext.getBeansOfType(MigrationCompletedAware.class);
        for (MigrationCompletedAware invoker : results.values()) {
            invoker.MigrationCompleted();
        }
    }
}
