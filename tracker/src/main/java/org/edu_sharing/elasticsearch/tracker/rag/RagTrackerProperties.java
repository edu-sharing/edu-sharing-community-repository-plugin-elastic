package org.edu_sharing.elasticsearch.tracker.rag;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Settings of the RAG tracker, following the {@code tracker.content} shape.
 * <p>
 * Disabled by default: the first run walks the entire transaction history and embeds every node in
 * the repository, which is the one genuinely expensive moment in this pipeline. Turning it on is a
 * deliberate act.
 * <p>
 * Only the settings shared by every profile live here. Model, dimensions and chunk sizes belong to
 * {@link RagProfile}, because they are what makes one index's vectors incomparable with another's.
 */
@Data
@Component
@EqualsAndHashCode(callSuper = true)
@ConfigurationProperties(prefix = "tracker.rag")
public class RagTrackerProperties extends AlfTransactionTrackerProperties {

    /** Which backend serves the full text, mirroring {@code tracker.content.api}. */
    public enum Api {
        Alfresco,
        EduSharing
    }

    private Api api = Api.Alfresco;

    /**
     * Safety valve against a single pathological document, in characters. Zero means unlimited -
     * unlike {@code tracker.content.maxContentLength}, which truncates at 10 MB, because a chunk
     * index that silently stops at page 400 of a textbook is worse than one that is slow.
     * {@code maxChunksPerNode} is the bound that actually matters.
     */
    private int maxContentLength = 0;

    /** Alias the search reads; the active profile points it at its own index at every start. */
    private String alias = "rag_chunks";
}
