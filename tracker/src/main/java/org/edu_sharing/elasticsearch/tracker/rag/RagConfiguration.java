package org.edu_sharing.elasticsearch.tracker.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Switches the whole RAG pipeline on.
 * <p>
 * Off by default, because the first run walks the entire transaction history and embeds every node
 * in the repository - the one genuinely expensive moment in this design, and not something to start
 * by accident.
 */
@Configuration
@ConditionalOnProperty(name = "tracker.rag.enabled", havingValue = "true")
public class RagConfiguration {

    /**
     * Static so that declaring the registrar does not force this configuration class - and with it
     * anything it might inject - to be instantiated before the bean factory is ready.
     */
    @Bean
    public static RagTrackerRegistrar ragTrackerRegistrar() {
        return new RagTrackerRegistrar();
    }
}
