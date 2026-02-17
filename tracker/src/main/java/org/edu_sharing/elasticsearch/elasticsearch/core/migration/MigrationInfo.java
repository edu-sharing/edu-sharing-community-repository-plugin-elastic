package org.edu_sharing.elasticsearch.elasticsearch.core.migration;

import lombok.Value;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.jobs.MigrationJob;
import org.edu_sharing.elasticsearch.tracker.core.TrackerConfig;
import org.edu_sharing.elasticsearch.tracker.strategy.CommiteTimeStatus;

import java.util.Set;
import java.util.function.Supplier;

@Value
public class MigrationInfo {
    /**
     * Version should be unique name
     * It will be appended on workspace and transactions index as a postfix
     * It's also used identifier for the migration status index
     */
    String version;


    Set<Class<? extends TrackerConfig<?, ? extends CommiteTimeStatus>>> migrateTrackerConfigs;



    /**
     * A callback interface used during the migration process to execute custom logic at a particular stage.
     * The implementation of this callback can contain logic specific to the migration process such as
     * additional validations, transformations, or post-migration actions.
     * </br>
     * The {@link MigrationCallback} is invoked during the migration process according to the workflow
     * orchestrated by the {@link MigrationJob}. It can access both the job details and a client instance
     * for further actions through the callback method.
     */
    Supplier<MigrationCallback> callbackProvider;

    public MigrationCallback getCallback() {
        if(callbackProvider == null){
            return null;
        }
        return callbackProvider.get();
    }

}
