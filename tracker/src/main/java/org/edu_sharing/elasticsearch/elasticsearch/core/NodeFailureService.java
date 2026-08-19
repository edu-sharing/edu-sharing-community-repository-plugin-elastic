package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.UpdateRequest;
import co.elastic.clients.json.JsonData;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.utils.ElasticErrorClassifier;
import org.edu_sharing.elasticsearch.metric.NodeFailureMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Dead letter queue for nodes that could not be indexed.
 * <p>
 * A skipped node advances the transaction marker, so the regular tracking will never come back to
 * it, and there is no automatic reprocessing. Recording it here keeps it findable and repairable
 * instead of losing it in a log line.
 * <p>
 * Documents are keyed by {@code <tracker>:<dbid>}: the tracker is what the node would have to be
 * replayed through, so one document is exactly one unit of repair. Repeated failures update that one
 * document instead of piling up, and it is deleted again as soon as the node is resolved - the
 * document count is therefore the number of open problems, and no retention policy is needed.
 */
@Slf4j
@Component
public class NodeFailureService {

    /** longer messages carry no extra information and only bloat the index */
    private static final int MAX_REASON_LENGTH = 2000;

    // language=painless
    private static final String SCRIPT_TOUCH = """
            ctx._source.attempts += 1;
            ctx._source.lastSeen = params.lastSeen;
            ctx._source.errorType = params.errorType;
            ctx._source.errorReason = params.errorReason;
            ctx._source.source = params.source;
            ctx._source.operation = params.operation;
            if (params.txnId != null) { ctx._source.txnId = params.txnId; }
            if (params.nodeType != null) { ctx._source.nodeType = params.nodeType; }
            """;

    private final ElasticsearchClient client;
    private final String index;
    private final NodeFailureMetrics nodeFailureMetrics;

    @Value("${elastic.update.retryOnConflict:5}")
    int updateRetryOnConflict;

    public NodeFailureService(ElasticsearchClient client,
                              IndexConfiguration nodeFailures,
                              NodeFailureMetrics nodeFailureMetrics) {
        this.client = client;
        this.index = nodeFailures.getIndex();
        this.nodeFailureMetrics = nodeFailureMetrics;
    }

    /**
     * Identifies a node that could not be indexed.
     *
     * @param tracker   tracker the node would have to be replayed through, part of the document id
     * @param source    where it broke - tracker name or the method that skipped it, used as metric tag
     * @param operation what was attempted, e.g. {@code update} or {@code index}
     * @param dbid      alfresco node db id, part of the document id
     * @param txnId     may be null if the caller does not know it
     * @param nodeType  may be null
     */
    public record NodeFailure(String tracker, String source, String operation, long dbid,
                              String nodeRef, String nodeType, Long txnId) {
    }

    /** @see #record(NodeFailure, String, String) */
    public void record(NodeFailure failure, Throwable throwable) {
        record(failure, ElasticErrorClassifier.errorType(throwable), throwable.getMessage());
    }

    /** @see #record(NodeFailure, String, String) */
    public void record(NodeFailure failure, ErrorCause error) {
        record(failure, ElasticErrorClassifier.errorType(error), error == null ? null : error.reason());
    }

    /**
     * Records a node that had to be skipped. Never throws - failing to write the dead letter entry
     * must not abort the tracking run that is trying to recover from a failure in the first place.
     */
    public void record(NodeFailure failure, String errorType, String errorReason) {
        nodeFailureMetrics.countSkippedNode(failure.source(), errorType);

        String id = documentId(failure.tracker(), failure.dbid());
        String now = Instant.now().toString();
        String reason = truncate(errorReason);

        Map<String, JsonData> params = new HashMap<>();
        params.put("lastSeen", JsonData.of(now));
        params.put("errorType", JsonData.of(errorType));
        params.put("errorReason", JsonData.of(reason));
        params.put("source", JsonData.of(failure.source()));
        params.put("operation", JsonData.of(failure.operation()));
        if (failure.txnId() != null) {
            params.put("txnId", JsonData.of(failure.txnId()));
        }
        if (failure.nodeType() != null) {
            params.put("nodeType", JsonData.of(failure.nodeType()));
        }

        Map<String, Object> initial = new HashMap<>();
        initial.put("tracker", failure.tracker());
        initial.put("source", failure.source());
        initial.put("operation", failure.operation());
        initial.put("dbid", failure.dbid());
        initial.put("nodeRef", failure.nodeRef());
        initial.put("nodeType", failure.nodeType());
        initial.put("txnId", failure.txnId());
        initial.put("errorType", errorType);
        initial.put("errorReason", reason);
        initial.put("firstSeen", now);
        initial.put("lastSeen", now);
        initial.put("attempts", 1L);

        try {
            // TDocument is pinned to JsonData by upsert(...), so the response type has to match
            UpdateRequest<JsonData, JsonData> request = UpdateRequest.of(u -> u
                    .index(index)
                    .id(id)
                    .retryOnConflict(updateRetryOnConflict)
                    .script(s -> s.source(SCRIPT_TOUCH).params(params))
                    .upsert(JsonData.of(initial)));
            client.update(request, JsonData.class);
        } catch (Exception e) {
            log.warn("could not record node failure {} in {}: {}", id, index, e.getMessage());
        }
    }

    /**
     * Removes a node from the dead letter index because it could be indexed again or does not exist
     * anymore. Deleting a missing entry is a no-op. Never throws.
     */
    public void resolve(String tracker, long dbid) {
        String id = documentId(tracker, dbid);
        try {
            client.delete(d -> d.index(index).id(id));
        } catch (Exception e) {
            log.warn("could not resolve node failure {} in {}: {}", id, index, e.getMessage());
        }
    }

    /**
     * Keeps {@link NodeFailureMetrics#GAUGE_NODE_FAILURES_PENDING} in sync with the index. Pulling
     * the value on scrape would put elasticsearch in the path of every prometheus request, so it is
     * refreshed on a slow schedule instead.
     */
    @Scheduled(fixedDelayString = "${elastic.nodeFailures.gaugeRefreshMs:60000}",
            initialDelayString = "${elastic.nodeFailures.gaugeRefreshMs:60000}")
    public void refreshPendingGauge() {
        try {
            nodeFailureMetrics.setPending(client.count(c -> c.index(index)).count());
        } catch (Exception e) {
            // index may not exist yet during startup or migration
            log.debug("could not refresh pending node failure gauge: {}", e.getMessage());
        }
    }

    private static String documentId(String tracker, long dbid) {
        return tracker + ":" + dbid;
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return "";
        }
        return reason.length() <= MAX_REASON_LENGTH ? reason : reason.substring(0, MAX_REASON_LENGTH);
    }
}
