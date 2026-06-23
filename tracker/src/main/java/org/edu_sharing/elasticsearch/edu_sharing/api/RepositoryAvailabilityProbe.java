package org.edu_sharing.elasticsearch.edu_sharing.api;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.generated.repository.backend.services.rest.client.api.AboutApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Probes the edu-sharing repository for readiness via {@code GET /_about}.
 * <p>
 * Used to defer repository access until the repository is actually up: the migration only
 * touches the repository in the document tracking phase, and the default-mode tracker only
 * needs the repository once it starts tracking. The wait therefore no longer happens in the
 * container entrypoint but lazily inside the application, right before the repository is used.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepositoryAvailabilityProbe {

    private final AboutApi aboutApi;

    @Value("${repository.readiness.pollInterval:5s}")
    private Duration pollInterval;

    /**
     * @return {@code true} if the repository answered {@code GET /_about}, {@code false} otherwise
     */
    public boolean isAvailable() {
        try {
            return aboutApi.about().block() != null;
        } catch (Exception e) {
            log.debug("Repository not available yet: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Blocks until the repository becomes available, polling every {@code repository.readiness.pollInterval}.
     */
    public void waitUntilAvailable() {
        while (!isAvailable()) {
            log.info("Waiting for repository ...");
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for repository", e);
            }
        }
    }
}