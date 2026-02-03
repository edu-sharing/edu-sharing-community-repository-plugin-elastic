package org.edu_sharing.elasticsearch.tracker;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.ClearScrollRequest;
import co.elastic.clients.elasticsearch.core.ScrollRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.ResponseBody;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Vergleich von Dokumenten zweier Indizes nach NodeId
 */
@Slf4j
public class ElasticIndexContentComparator {

    // === Diff Framework (wie vorher) ===
    enum DiffLevel { FAIL, WARN }
    record DiffRule(DiffLevel level, boolean ignore, boolean ignoreNotNullMismatch) {}
    record DiffEntry(String path, Object left, Object right, DiffLevel level) {}
    record DiffReport(String id, String uuid, List<DiffEntry> failures, List<DiffEntry> warnings) {}

    private static final Map<String, DiffRule> FIELD_RULES = Map.ofEntries(
            // Ignore
            Map.entry("_score", new DiffRule(null, true,false)),
            Map.entry("properties.sys:node-dbid", new DiffRule(null, true,false)),
            Map.entry("properties.cm:lastThumbnailModification", new DiffRule(null, true,false)),
            Map.entry("properties.cm:content.contentId", new DiffRule(null, true,false)),
            Map.entry("content.contentId", new DiffRule(null, true,false)),
            Map.entry("properties.cm:modified", new DiffRule(null, true,false)),
            Map.entry("preview", new DiffRule(DiffLevel.WARN, false,true)),

            // Warn
            Map.entry("properties.cm:modifier", new DiffRule(DiffLevel.WARN, false,false)),
            Map.entry("properties.cm:creator", new DiffRule(DiffLevel.WARN, false,false)),
            Map.entry("owner", new DiffRule(DiffLevel.WARN, false,false)),


            // Fail
            Map.entry("properties.cm:created", new DiffRule(DiffLevel.FAIL, false,false)),
            Map.entry("txnId", new DiffRule(DiffLevel.FAIL, false,false)),
            Map.entry("dbid", new DiffRule(DiffLevel.FAIL, false,false)),
            Map.entry("aclId", new DiffRule(DiffLevel.FAIL, false,false))
    );

    // === Main ===
    public static void main(String[] args) throws IOException {
        String indexA = "workspace_10.0_bak";
        String indexB = "workspace_10.0";
        String type = "ccm:io";

        // Elasticsearch client
        RestClient restClient = RestClient.builder(new HttpHost("localhost", 9200)).build();
        ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        ElasticsearchClient client = new ElasticsearchClient(transport);

        // Alle NodeIds eines Typs aus IndexA
        log.info("fetching nodeIds");
        List<String> nodeIds = fetchAllNodeIds(client, indexA, type, 100000).stream().sorted().toList();
        log.info("fetching nodeIds finished {}", nodeIds.size());

        int pageSize = 30000;
        int pageNumber = 1;

        int fromIndex = pageNumber * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, nodeIds.size());

        if (fromIndex >= nodeIds.size()) {
            throw new IllegalArgumentException("Page out of range");
        }

        List<String> pageNodeIds = nodeIds.subList(fromIndex, toIndex);
        log.info("Comparing page {} ({}–{})",
                pageNumber, fromIndex, toIndex);

        // Parallel vergleichen
        AtomicInteger counter = new AtomicInteger();
        List<DiffReport> reports = pageNodeIds
                .parallelStream()
                .map(nodeId -> {
                    int current = counter.incrementAndGet();

                    if (current % 100 == 0) {
                        log.info("Compared " + current + " / " + pageNodeIds.size() + " nodes");
                    }

                    return compareByNode(client, nodeId, indexA, indexB);
                })
                .collect(Collectors.toList());

        reports = reports.stream().filter(r -> !r.failures().isEmpty() || !r.warnings().isEmpty()).toList();

        writeJsonReport(reports, "diff-report.json");

        restClient.close();
    }

    // === Fetch all Node IDs for a type using scroll ===
    private static Set<String> fetchAllNodeIds(ElasticsearchClient client, String index, String type, int limit) {
        Set<String> ids = new HashSet<>();
        String scrollId = null;
        try {
            final int PAGE_SIZE = 1000;
            final String SCROLL_TIMEOUT = "2m";

            SearchRequest search = new SearchRequest.Builder()
                    .index(index)
                    .query(q -> q
                            .term(t -> t
                                    .field("type")
                                    .value(type)
                            ))
                    .size(PAGE_SIZE)
                    .scroll(s -> s.time(SCROLL_TIMEOUT))
                    .source(src -> src.filter(f -> f.includes("_id")))
                    .build();

            ResponseBody<Map> resp = client.search(search, Map.class);
            scrollId = resp.scrollId();

            while (true) {
                for (Hit<Map> hit : resp.hits().hits()) {
                    ids.add(hit.id());
                    if(ids.size() >= limit) {
                        System.out.println("limit reached");
                        return ids;
                    }
                }

                if (resp.hits().hits().isEmpty()) break;

                resp = client.scroll(new ScrollRequest.Builder()
                                .scrollId(scrollId)
                                .scroll(s -> s.time(SCROLL_TIMEOUT))
                                .build(),
                        Map.class);
                scrollId = resp.scrollId();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }finally {
            if (scrollId != null) {
                // clear scroll context
                try {
                    client.clearScroll(new ClearScrollRequest.Builder().scrollId(scrollId).build());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return ids;
    }

    // === Compare single node ===
    private static DiffReport compareByNode(ElasticsearchClient client, String nodeId, String indexA, String indexB) {
        try {
            Map<String, Object> docA = loadFromIndex(client, indexA, nodeId);
            Map<String, Object> docB = loadFromIndex(client, indexB, nodeId);

            List<DiffEntry> failures = new ArrayList<>();
            List<DiffEntry> warnings = new ArrayList<>();
            compareRecursive(docA, docB, "", failures, warnings);

            String uuid = (String)((Map)docA.get("nodeRef")).get("id");

            return new DiffReport(nodeId, uuid, failures, warnings);
        } catch (Exception e) {
            DiffEntry entry = new DiffEntry("nodeFetchError", e.getMessage(), null, DiffLevel.FAIL);
            return new DiffReport(nodeId, null, Collections.singletonList(entry), Collections.emptyList());
        }
    }

    // === Load document by NodeId ===
    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadFromIndex(ElasticsearchClient client, String index, String nodeId) throws IOException {
        try {
            var resp = client.get(g -> g.index(index).id(nodeId), Map.class);
            if (resp.found()) {
                return (Map<String, Object>) resp.source();
            } else {
                return Collections.emptyMap();
            }
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    // === Recursive diff logic ===
    @SuppressWarnings("unchecked")
    private static void compareRecursive(
            Object left,
            Object right,
            String path,
            List<DiffEntry> failures,
            List<DiffEntry> warnings
    ) {
        // Spezielle Behandlung: properties.cm:modified -> nur log, kein diff
        if (path.endsWith("properties.cm:modified") || path.endsWith("cm:modified")) {
            if (!Objects.equals(left, right)) {
                System.out.println("MODIFIED MISMATCH at " + path + ": left=" + left + " | right=" + right);
            }
            return; // kein Fail / Warn
        }

        DiffRule rule = resolveRule(path);
        if (rule.ignore()) return;

        DiffLevel level = rule.level() != null ? rule.level() : DiffLevel.FAIL;

        if (left == null && right == null) return;
        if (left == null || right == null) {
            addDiff(path, left, right, level, failures, warnings);
            return;
        }

        if (left instanceof Map && right instanceof Map) {
            Map<String, Object> m1 = (Map<String, Object>) left;
            Map<String, Object> m2 = (Map<String, Object>) right;
            Set<String> keys = new HashSet<>();
            keys.addAll(m1.keySet());
            keys.addAll(m2.keySet());
            for (String key : keys) {
                String newPath = path.isEmpty() ? key : path + "." + key;
                compareRecursive(m1.get(key), m2.get(key), newPath, failures, warnings);
            }
            return;
        }

        if (left instanceof List && right instanceof List) {
            if (!left.equals(right)) {
                addDiff(path, left, right, level, failures, warnings);
            }
            return;
        }

        // created = strict fail
        if (path.endsWith("cm:created")) {
            if (!Objects.equals(left, right)) {
                addDiff(path, left, right, DiffLevel.FAIL, failures, warnings);
            }
            return;
        }

        if (!Objects.equals(left, right)) {
            if(!rule.ignoreNotNullMismatch())
                addDiff(path, left, right, level, failures, warnings);
        }
    }

    private static DiffRule resolveRule(String path) {
        return FIELD_RULES.entrySet().stream()
                .filter(e -> path.startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(new DiffRule(DiffLevel.FAIL, false,false));
    }

    private static void addDiff(
            String path,
            Object left,
            Object right,
            DiffLevel level,
            List<DiffEntry> failures,
            List<DiffEntry> warnings
    ) {
        DiffEntry diff = new DiffEntry(path, left, right, level);
        if (level == DiffLevel.FAIL) failures.add(diff);
        else warnings.add(diff);
    }

    private static void writeJsonReport(List<DiffReport> reports, String file) throws IOException {
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(Path.of(file).toFile(), reports);
    }
}


