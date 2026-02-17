package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import lombok.Builder;
import lombok.Getter;
import org.edu_sharing.elasticsearch.tracker.core.TrackerConfig;
import org.edu_sharing.elasticsearch.tracker.strategy.CommiteTimeStatus;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Builder(toBuilder = true)
public final class MigrationContext {
    @Getter
    private final String sourceWorkspaceIndex;
    @Getter
    private final String sourceTrackerStateIndex;
    @Getter
    private final String sourceAuthoritiesIndex;
    @Getter
    private final String targetWorkspaceIndex;
    @Getter
    private final String targetTrackerStateIndex;
    @Getter
    private final String targetAuthoritiesIndex;
    @Getter
    private final String toVersion;
    @Getter
    private final String migrationsIndex;
    @Getter
    private final String migrationTrackerStateIndex;
    @Getter
    private final Set<Class<? extends TrackerConfig<?, ? extends CommiteTimeStatus>>> migrationTracker;
    @Getter
    private final List<MigrationCallback> migrationCallbacks;
    private final Supplier<String> migrationContentSupplier;
    private final Consumer<String> migrationContentConsumer;


    public void setMigrationContent(String content) {
        migrationContentConsumer.accept(content);
    }

    public String getMigrationContent() {
        return migrationContentSupplier.get();
    }

}
