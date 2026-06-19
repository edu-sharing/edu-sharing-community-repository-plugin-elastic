package org.edu_sharing.elasticsearch.elasticsearch.core;

import java.util.concurrent.atomic.AtomicBoolean;

public class DefaultApplicationState implements ApplicationState, ApplicationStatePublisher {
    private final AtomicBoolean migrationCompleted = new AtomicBoolean(false);
    private final AtomicBoolean hooksCompleted = new AtomicBoolean(false);

    @Override
    public void markMigrationCompleted() {
        migrationCompleted.set(true);
    }

    @Override
    public void markHooksCompleted() {
        hooksCompleted.set(true);
    }

    @Override
    public boolean canTrack() {
        return migrationCompleted.get() && hooksCompleted.get();
    }
}
