package org.edu_sharing.elasticsearch.elasticsearch.utils;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch._types.ErrorResponse;

import java.util.Set;

/**
 * Decides whether an Elasticsearch failure is caused by a single document or by the connection /
 * cluster as a whole.
 * <p>
 * A node level failure may be skipped so the tracker can continue with the remaining nodes of the
 * batch. Everything else has to be propagated, otherwise a temporary outage would silently advance
 * the transaction marker and the affected nodes would never be picked up again.
 * <p>
 * The classification is an allow list on purpose: unknown error types are treated as
 * infrastructure problems and therefore retried, never swallowed.
 */
public final class ElasticErrorClassifier {

    /**
     * Errors that describe a problem of the document itself and will not go away by retrying.
     * Only meaningful for document operations - the same type on a query means broken code.
     */
    private static final Set<String> NODE_LEVEL_ERRORS = Set.of(
            "document_missing_exception",
            "document_parsing_exception",
            "mapper_parsing_exception",
            "strict_dynamic_mapping_exception",
            "illegal_argument_exception");

    private ElasticErrorClassifier() {
    }

    /**
     * @return true if the failure belongs to a single document and the caller may skip it,
     * false if the caller has to propagate it so the batch is retried
     */
    public static boolean isNodeLevel(Throwable throwable) {
        if (!(throwable instanceof ElasticsearchException elasticsearchException)
                || elasticsearchException.response() == null) {
            return false;
        }
        return isNodeLevel(elasticsearchException.status(), elasticsearchException.error());
    }

    /**
     * Same decision for a failed bulk item, which reports its problem as data instead of throwing.
     */
    public static boolean isNodeLevel(int status, ErrorCause error) {
        if (error == null) {
            return false;
        }
        return (status == 400 || status == 404) && NODE_LEVEL_ERRORS.contains(error.type());
    }

    /**
     * @return the elasticsearch error type, used as a bounded metric tag
     */
    public static String errorType(Throwable throwable) {
        if (throwable instanceof ElasticsearchException elasticsearchException
                && elasticsearchException.error() != null) {
            return elasticsearchException.error().type();
        }
        return throwable.getClass().getSimpleName();
    }

    public static String errorType(ErrorCause error) {
        return error == null ? "unknown" : error.type();
    }

    /**
     * Turns a failed bulk item back into the exception it would have been on a single document
     * request, so callers can propagate it with the original type and reason intact.
     */
    public static ElasticsearchException toException(String endpointId, int status, ErrorCause error) {
        return new ElasticsearchException(endpointId, ErrorResponse.of(r -> r.status(status).error(error)));
    }
}
