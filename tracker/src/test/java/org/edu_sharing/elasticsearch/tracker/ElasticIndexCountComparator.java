package org.edu_sharing.elasticsearch.tracker;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.ResponseBody;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ElasticIndexCountComparator {

    private static final int PAGE_SIZE = 1000;
    private static final String SCROLL_TIMEOUT = "2m";

    public static void main(String[] args) throws IOException {

        String index1 = "workspace_10.0_bak";
        String index2 = "workspace_10.0";

        RestClient restClient = RestClient.builder(
                new HttpHost("localhost", 9200)
        ).build();

        ElasticsearchTransport transport =
                new RestClientTransport(restClient, new JacksonJsonpMapper());

        ElasticsearchClient client = new ElasticsearchClient(transport);

        compareIndices(client, index1, index2,"ccm:io");
        //compareIndices(client,"authorities_10.0_bak","authorities_10.0","cm:person");

        restClient.close();
    }

    /**
     * Your query, translated 1:1
     */
    private static Query baseQuery(String type) {
        return Query.of(q -> q
                .bool(b -> b
                        .must(m -> m
                                .term(t -> t
                                        .field("type")
                                        .value(FieldValue.of(type))
                                )
                        )
                        .must(m -> m
                                .term(t -> t
                                        .field("nodeRef.storeRef.protocol")
                                        .value(FieldValue.of("workspace"))
                                )
                        )
                )
        );
    }

    /**
     * Fetch all matching IDs from an index using Scroll API
     */
    private static Map<String, Map<String, Object>> fetchIdsWithScroll(
            ElasticsearchClient client,
            String index, String type
    ) throws IOException {

        Map<String, Map<String, Object>> docs = new HashMap<>();


        SearchRequest searchRequest = new SearchRequest.Builder()
                .index(index)
                .query(baseQuery(type))
                .size(PAGE_SIZE)
                .scroll(s -> s.time(SCROLL_TIMEOUT))
                .source(src -> src.filter(f -> f.includes("_id","nodeRef","type","properties.ccm:editorial_state")))
                .trackTotalHits(t -> t.enabled(true))
                .build();

        ResponseBody<Map> searchResponse =
                client.search(searchRequest, Map.class);

        String scrollId = searchResponse.scrollId();

        while (true) {
            System.out.println("ids size: " + docs.size());
            if (searchResponse.hits().hits().isEmpty()) {
                break;
            }

            for (Hit<Map> hit : searchResponse.hits().hits()) {
                docs.put(hit.id(), hit.source());
            }

            searchResponse = client.scroll(
                    new ScrollRequest.Builder()
                            .scrollId(scrollId)
                            .scroll(s -> s.time(SCROLL_TIMEOUT))
                            .build(),
                    Map.class
            );

            scrollId = searchResponse.scrollId();
        }

        // cleanup scroll context
        client.clearScroll(
                new ClearScrollRequest.Builder()
                        .scrollId(scrollId)
                        .build()
        );
        return docs;
    }

    /**
     * Compare index1 vs index2
     */
    private static void compareIndices(
            ElasticsearchClient client,
            String index1,
            String index2, String type
    ) throws IOException {

        System.out.println("Fetching matching documents from " + index1);
        Map<String, Map<String, Object>> index1Ids = fetchIdsWithScroll(client, index1, type);

        System.out.println("Fetching matching documents from " + index2);
        Map<String, Map<String, Object>> index2Ids = fetchIdsWithScroll(client, index2, type);

        Set<String> missingInIndex2 = index1Ids.keySet().stream()
                .filter(id -> !index2Ids.keySet().contains(id))
                .collect(Collectors.toSet());

        System.out.println("-------------------------------------------------");
        System.out.println("Index1 matching docs: " + index1Ids.size());
        System.out.println("Index2 matching docs: " + index2Ids.size());
        System.out.println("Missing in index2:    " + missingInIndex2.size());
        System.out.println("-------------------------------------------------");

        missingInIndex2.forEach(id ->
                System.out.println("Missing document ID: " + id +" "
                        +index1Ids.get(id).get("nodeRef")));
                        //.get("properties"))
                        //.get("ccm:editorial_state"))
    }
}
