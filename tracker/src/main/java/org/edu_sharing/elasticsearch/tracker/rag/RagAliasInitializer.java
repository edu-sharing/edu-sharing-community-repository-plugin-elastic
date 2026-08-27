package org.edu_sharing.elasticsearch.tracker.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkService;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagIndexMetadata;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.Order;

import java.io.IOException;

/**
 * Points the search alias at the active profile's index, on every start.
 * <p>
 * Deliberately not an {@code ApplicationStartupHook}: those are recorded in the {@code hooks} index
 * and run exactly once for the lifetime of an installation. The alias is the opposite - it has to be
 * re-asserted at every start, because switching a profile means changing configuration and
 * restarting, and a hook would leave the alias pointing at the old index.
 * <p>
 * Ordered between the hook service (-100) and {@code TrackerScheduler} (0): the indices themselves
 * already exist by then, since {@code AdminService} creates them in {@code @PostConstruct}.
 */
@Slf4j
@Order(-50)
@RequiredArgsConstructor
public class RagAliasInitializer implements SmartInitializingSingleton {

    private final RagChunkService activeProfile;
    private final String alias;
    private final RagIndexMetadata configured;

    @Override
    public void afterSingletonsInstantiated() {
        try {
            verifyModelMatches();
            activeProfile.pointAliasHere(alias);
        } catch (IOException e) {
            // without the alias every search would hit a missing index, so this is fatal
            throw new IllegalStateException(
                    "could not point alias " + alias + " at " + activeProfile.getIndex(), e);
        }
    }

    /**
     * Refuses to serve an index whose vectors a different model produced.
     * <p>
     * {@code AdminService} only creates missing indices, so pointing a profile at a name that
     * already exists silently reuses whatever is in it. Vectors from two models share no space:
     * nothing would fail, every search would just return subtly wrong neighbours. Since the index
     * records what built it, that mistake can be caught here instead of never.
     */
    private void verifyModelMatches() throws IOException {
        RagIndexMetadata actual = activeProfile.readMetadata();
        if (actual == null) {
            // an index written before this was recorded; nothing to compare against
            log.warn("index {} carries no embedding metadata - cannot verify it was built by {}",
                    activeProfile.getIndex(), configured.model());
            return;
        }
        if (!actual.isCompatibleWith(configured)) {
            throw new IllegalStateException("index " + activeProfile.getIndex() + " was built with "
                    + actual.model() + " (" + actual.dimensions() + " dimensions, "
                    + actual.similarity() + ") but the active profile configures "
                    + configured.model() + " (" + configured.dimensions() + " dimensions, "
                    + configured.similarity() + "). Vectors from two models are not comparable - "
                    + "give the new profile its own id so it builds its own index.");
        }
        log.info("index {} confirmed as built by {} ({} dimensions)",
                activeProfile.getIndex(), actual.model(), actual.dimensions());
    }
}
