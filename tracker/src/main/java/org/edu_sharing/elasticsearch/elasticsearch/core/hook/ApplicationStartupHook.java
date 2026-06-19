package org.edu_sharing.elasticsearch.elasticsearch.core.hook;

import java.io.IOException;

/**
 * Interface for hooks that run once during application startup,
 * independent of the application version.
 */
public interface ApplicationStartupHook {

    /**
     * Execute the hook logic.
     * This method is called once during application startup, before trackers are scheduled.
     *
     * @throws IOException if an error occurs during hook execution
     */
    void execute() throws IOException;

    /**
     * Get the unique name of this hook.
     * Used to track whether the hook has already been executed.
     *
     * @return the hook name
     */
    String getName();

    /**
     * Get the order/priority of this hook.
     * Hooks with lower order values are executed first.
     *
     * @return the order value (default: 0)
     */
    default int getOrder() {
        return 0;
    }
}
