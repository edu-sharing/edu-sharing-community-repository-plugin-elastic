package org.edu_sharing.elasticsearch.tracker.rag;

import org.apache.commons.lang3.StringUtils;
import org.edu_sharing.elasticsearch.alfresco.client.NodeData;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.alfresco.client.Path;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkDocument;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkMetadata;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.rag.chunking.ChunkSource;
import org.edu_sharing.repository.client.tools.CCConstants;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns one {@link NodeData} into the two things the RAG pipeline needs: the text and labels that go
 * into chunking, and the fields that go into the chunk document.
 * <p>
 * <strong>The two are deliberately not the same values.</strong> Facets are stored raw - the same
 * vocabulary URIs the workspace index holds - so a filter written against one index means the same
 * thing against the other. The chunk source instead gets the resolved German labels, because
 * "Mathematik" is what a reader searches for and what an embedding model can place;
 * {@code http://w3id.org/openeduhub/vocabs/discipline/380} is not.
 * <p>
 * Access is not built here. Who may see a node depends on the collections holding a copy of it,
 * which is nowhere in {@link NodeData} - see {@link RagAccessResolver}.
 * <p>
 * Free of Elasticsearch and Spring so the mapping decisions can be tested on their own.
 */
public final class RagNodeMapper {

    /** Language whose valuespace labels feed the chunk text, matching the German analyzer. */
    private static final String LABEL_LANGUAGE = "de";

    private static final String PROP_TITLE = "cclom:title";
    private static final String PROP_NAME = "cm:name";
    private static final String PROP_DESCRIPTION = "cclom:general_description";
    private static final String PROP_KEYWORD = "cclom:general_keyword";
    private static final String PROP_SUBJECT = "ccm:taxonid";
    private static final String PROP_EDUCATIONAL_CONTEXT = "ccm:educationalcontext";
    private static final String PROP_RESOURCE_TYPE = "ccm:oeh_lrt";
    private static final String PROP_LICENSE = "ccm:commonlicense_key";
    private static final String PROP_LANGUAGE = "cclom:general_language";
    private static final String PROP_SOURCE = "ccm:replicationsource";
    private static final String PROP_CREATED = "cm:created";
    private static final String PROP_MODIFIED = "cm:modified";

    private RagNodeMapper() {
    }

    /** What gets chunked and embedded: raw text plus human-readable labels. */
    public static ChunkSource toChunkSource(NodeData nodeData) {
        NodeMetadata node = nodeData.getNodeMetadata();
        Map<String, Serializable> properties = properties(node);
        Map<String, List<String>> labels = labels(nodeData);

        return new ChunkSource(
                uuid(node),
                mimetype(properties),
                nodeData.getFullText(),
                firstNonBlank(single(properties, PROP_TITLE), single(properties, PROP_NAME)),
                single(properties, PROP_DESCRIPTION),
                multi(properties, PROP_KEYWORD),
                labelled(labels, properties, PROP_SUBJECT),
                labelled(labels, properties, PROP_EDUCATIONAL_CONTEXT),
                labelled(labels, properties, PROP_RESOURCE_TYPE));
    }

    /** What gets stored alongside the vector: filter values, access control, provenance. */
    public static RagChunkMetadata toMetadata(NodeData nodeData) {
        NodeMetadata node = nodeData.getNodeMetadata();
        Map<String, Serializable> properties = properties(node);

        return new RagChunkMetadata(
                node.getId(),
                node.getAclId(),
                node.getOwner(),
                node.getType(),
                node.getAspects() == null ? List.of() : List.copyOf(node.getAspects()),
                primaryPath(node),
                allPaths(node),
                new RagChunkDocument.Facets(
                        multi(properties, PROP_SUBJECT),
                        multi(properties, PROP_EDUCATIONAL_CONTEXT),
                        multi(properties, PROP_RESOURCE_TYPE),
                        single(properties, PROP_LICENSE),
                        single(properties, PROP_LANGUAGE),
                        single(properties, PROP_SOURCE),
                        mimetype(properties),
                        single(properties, PROP_CREATED),
                        single(properties, PROP_MODIFIED)),
                flattened(nodeData));
    }

    public static String uuid(NodeMetadata node) {
        String nodeRef = node.getNodeRef();
        int slash = nodeRef.lastIndexOf('/');
        return slash < 0 ? nodeRef : nodeRef.substring(slash + 1);
    }

    /**
     * Same construction as {@code WorkspaceService.addNodePath}, so a folder filter written for the
     * workspace index selects the same nodes here.
     */
    private static String primaryPath(NodeMetadata node) {
        List<Path> paths = node.getPaths();
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        return joinPath(paths.get(0));
    }

    private static List<String> allPaths(NodeMetadata node) {
        List<Path> paths = node.getPaths();
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        List<String> all = new ArrayList<>(paths.size());
        for (Path path : paths) {
            all.add(joinPath(path));
        }
        return all;
    }

    private static String joinPath(Path path) {
        String[] elements = path.getApath().split("/");
        return StringUtils.join(Arrays.asList(Arrays.copyOfRange(elements, 1, elements.length)), '/');
    }

    /**
     * Metadata-set properties whose shape is not fixed, following the {@code flattenedData}
     * precedent. Sorted, because the metadata fingerprint hashes this and map order would otherwise
     * make it unstable.
     */
    private static Map<String, Object> flattened(NodeData nodeData) {
        if (nodeData.getFlattenedData() == null || nodeData.getFlattenedData().isEmpty()) {
            return Map.of();
        }
        return new TreeMap<>(nodeData.getFlattenedData());
    }

    private static Map<String, Serializable> properties(NodeMetadata node) {
        if (node.getProperties() == null) {
            return Map.of();
        }
        Map<String, Serializable> byLocalName = new LinkedHashMap<>();
        for (Map.Entry<String, Serializable> property : node.getProperties().entrySet()) {
            String key = CCConstants.getValidLocalName(property.getKey());
            if (key != null) {
                byLocalName.put(key, property.getValue());
            }
        }
        return byLocalName;
    }

    /** Valuespace translations, keyed by local property name, for the label language. */
    private static Map<String, List<String>> labels(NodeData nodeData) {
        Map<String, Map<String, List<String>>> valueSpaces = nodeData.getValueSpaces();
        if (valueSpaces == null || valueSpaces.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> forLanguage = null;
        for (Map.Entry<String, Map<String, List<String>>> entry : valueSpaces.entrySet()) {
            if (entry.getKey() != null && entry.getKey().startsWith(LABEL_LANGUAGE)) {
                forLanguage = entry.getValue();
                break;
            }
        }
        if (forLanguage == null) {
            return Map.of();
        }
        Map<String, List<String>> byLocalName = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : forLanguage.entrySet()) {
            String key = CCConstants.getValidLocalName(entry.getKey());
            byLocalName.put(key == null ? entry.getKey() : key, entry.getValue());
        }
        return byLocalName;
    }

    /** Labels where the valuespace resolved them, raw values where it did not. */
    private static List<String> labelled(Map<String, List<String>> labels,
                                         Map<String, Serializable> properties, String property) {
        List<String> resolved = labels.get(property);
        if (resolved != null && !resolved.isEmpty()) {
            return List.copyOf(resolved);
        }
        return multi(properties, property);
    }

    private static String single(Map<String, Serializable> properties, String property) {
        List<String> values = multi(properties, property);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * Values as the workspace index stores them.
     * <p>
     * Runs through {@link WorkspaceService#getValue} rather than reimplementing it: Alfresco returns
     * localised properties as MLText, and calling {@code toString()} on one yields
     * {@code {locale=de_, value=Mathematik}} - noise in the citation line and in the text handed to
     * the model. Reusing the workspace index's own unwrapping also guarantees the facets here mean
     * exactly what a filter written against that index means.
     */
    private static List<String> multi(Map<String, Serializable> properties, String property) {
        Object value = WorkspaceService.getValue(properties.get(property), property);
        if (value == null) {
            return List.of();
        }
        if (value instanceof java.util.Collection<?> collection) {
            List<String> values = new ArrayList<>(collection.size());
            for (Object element : collection) {
                if (element != null && !element.toString().isBlank()) {
                    values.add(element.toString());
                }
            }
            return List.copyOf(values);
        }
        String text = value.toString();
        return text.isBlank() ? List.of() : List.of(text);
    }

    @SuppressWarnings("unchecked")
    private static String mimetype(Map<String, Serializable> properties) {
        Serializable content = properties.get("cm:content");
        if (content instanceof Map<?, ?> map) {
            Object mimetype = ((Map<String, Object>) map).get("mimetype");
            return mimetype == null ? null : mimetype.toString();
        }
        return null;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
