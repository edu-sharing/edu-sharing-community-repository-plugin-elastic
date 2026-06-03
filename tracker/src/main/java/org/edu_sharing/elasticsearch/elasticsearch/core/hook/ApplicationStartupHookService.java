package org.edu_sharing.elasticsearch.elasticsearch.core.hook;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.ApplicationStatePublisher;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service that manages and executes application startup hooks.
 * Runs after all beans are initialized but before TrackerScheduler starts scheduling trackers.
 */
@Slf4j
@Component
@Order(-100) // Execute before TrackerScheduler (which has @Order(0))
@RequiredArgsConstructor
public class ApplicationStartupHookService implements SmartInitializingSingleton {

    private final ElasticsearchClient client;
    private final List<ApplicationStartupHook> hooks;
    private final ApplicationStatePublisher applicationStatePublisher;

    private static final String INDEX_NAME = "hooks";

    @Override
    public void afterSingletonsInstantiated() {
        log.info("Starting application startup hooks execution");

        try {
            ensureHookExecutionIndexExists();

            // Sort hooks by order
            List<ApplicationStartupHook> sortedHooks = hooks.stream()
                    .sorted(Comparator.comparingInt(ApplicationStartupHook::getOrder))
                    .collect(Collectors.toList());

            for (ApplicationStartupHook hook : sortedHooks) {
                String hookName = hook.getName();

                if (hasHookBeenExecuted(hookName)) {
                    log.info("Hook '{}' has already been executed, skipping", hookName);
                    continue;
                }

                log.info("Executing hook '{}'", hookName);
                try {
                    hook.execute();
                    markHookAsExecuted(hookName);
                    log.info("Hook '{}' executed successfully", hookName);
                } catch (Exception e) {
                    log.error("Failed to execute hook '{}'", hookName, e);
                    throw new RuntimeException("Hook execution failed: " + hookName, e);
                }
            }

            log.info("All application startup hooks executed successfully");
            applicationStatePublisher.markHooksCompleted();
            log.info("Marked hooks as completed in application state");
        } catch (IOException e) {
            log.error("Failed to manage hook execution tracking", e);
            throw new RuntimeException("Hook execution tracking failed", e);
        }
    }

    private void ensureHookExecutionIndexExists() throws IOException {
        String indexName = getHookExecutionIndexName();
        boolean exists = client.indices().exists(req -> req.index(indexName)).value();

        if (!exists) {
            log.info("Creating hook execution tracking index: {}", indexName);
            client.indices().create(req -> req.index(indexName));
        }
    }

    private boolean hasHookBeenExecuted(String hookName) throws IOException {
        String indexName = getHookExecutionIndexName();

        SearchResponse<HookExecution> response = client.search(req -> req
                        .index(indexName)
                        .query(q -> q.ids(id -> id.values(hookName))),
                HookExecution.class);

        return !response.hits().hits().isEmpty();
    }

    private void markHookAsExecuted(String hookName) throws IOException {
        String indexName = getHookExecutionIndexName();

        HookExecution execution = new HookExecution();
        execution.setHookName(hookName);
        execution.setExecutedAt(new Date());

        client.index(req -> req
                .index(indexName)
                .id(hookName)
                .document(execution));
    }

    private String getHookExecutionIndexName() {
        return INDEX_NAME;
    }

    /**
     * Internal class to track hook executions
     */
    @lombok.Data
    private static class HookExecution {
        private String hookName;
        private Date executedAt;
    }
}
