package org.edu_sharing.elasticsearch.rag.chunking;

import java.util.List;

/**
 * Everything the chunker needs about one node. Assembled by the tracker from the Alfresco metadata
 * and the separately fetched full text; deliberately free of any Alfresco or Elasticsearch type so
 * that chunking stays testable on its own.
 *
 * @param nodeId              node UUID, used for log and error messages only
 * @param mimetype            {@code content.mimetype}; decides the segmentation strategy, may be null
 * @param fullText            extracted plain text, untruncated; may be null or blank
 * @param title               {@code cclom:title}
 * @param description         {@code cclom:general_description}
 * @param keywords            {@code cclom:general_keyword}
 * @param subject             resolved label of {@code ccm:taxonid}
 * @param educationalContext  resolved label of {@code ccm:educationalcontext}
 * @param learningResourceType resolved label of {@code ccm:oeh_lrt}
 */
public record ChunkSource(
        String nodeId,
        String mimetype,
        String fullText,
        String title,
        String description,
        List<String> keywords,
        List<String> subject,
        List<String> educationalContext,
        List<String> learningResourceType) {

    public ChunkSource {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        subject = subject == null ? List.of() : List.copyOf(subject);
        educationalContext = educationalContext == null ? List.of() : List.copyOf(educationalContext);
        learningResourceType = learningResourceType == null ? List.of() : List.copyOf(learningResourceType);
    }

    /** Metadata-only node: a link, an image, a video without a transcript. */
    public static ChunkSource metadataOnly(String nodeId, String title, String description) {
        return new ChunkSource(nodeId, null, null, title, description, null, null, null, null);
    }
}
