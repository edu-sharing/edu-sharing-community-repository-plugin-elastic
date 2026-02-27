package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;


@Slf4j
@Component
@RequiredArgsConstructor
public class MigrationCompletedBeanPostProcessor implements BeanPostProcessor, MigrationCompletedAware {

    private final ObjectProvider<WaitForMigrationJob> waitForMigrationJobProvider;

    private WaitForMigrationJob waitForMigrationJob;

    @Nullable
    @Override
    public Object postProcessAfterInitialization(@NotNull Object bean, @NotNull String beanName) throws BeansException {

        if (waitForMigrationJob == null) {
            return bean;
        }

        if (!waitForMigrationJob.isMigrationCompleted()) {
            return bean;
        }

        if (bean instanceof MigrationCompletedAware migrationCompletedAware) {
            log.debug("Migration completed, notifying {}", beanName);
            migrationCompletedAware.migrationCompleted();
        }

        return bean;
    }

    @Override
    public void migrationCompleted() {
        // we only need this if beans are initialized after the waitForMigrationJob
        waitForMigrationJob = waitForMigrationJobProvider.getObject();
    }
}
