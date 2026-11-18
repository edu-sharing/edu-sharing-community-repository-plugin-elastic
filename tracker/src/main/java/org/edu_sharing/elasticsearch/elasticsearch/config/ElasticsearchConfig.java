package org.edu_sharing.elasticsearch.elasticsearch.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.synonyms.ElasticsearchSynonymsClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class ElasticsearchConfig {

    @Value("${elastic.host}")
    String elasticHost;

    @Value("${elastic.port}")
    int elasticPort;

    @Value("${elastic.protocol}")
    String elasticProtocol;

    @Value("${elastic.socketTimeout}")
    int elasticSocketTimeout;

    @Value("${elastic.connectTimeout}")
    int elasticConnectTimeout;

    @Value("${elastic.connectionRequestTimeout}")
    int elasticConnectionRequestTimeout;

    @Value("${elastic.maxConnections}")
    int elasticMaxConnections;


    @Bean
    public RestClient restClient() {
        return RestClient.builder(new HttpHost(elasticHost, elasticPort, elasticProtocol))
                .setRequestConfigCallback(requestConfigBuilder -> requestConfigBuilder
                        .setConnectTimeout(elasticConnectTimeout)
                        .setSocketTimeout(elasticSocketTimeout)
                        .setConnectionRequestTimeout(elasticConnectionRequestTimeout))
                .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
                        .setMaxConnPerRoute(elasticMaxConnections)
                        .setMaxConnTotal(elasticMaxConnections))
                .build();
    }


    @Bean
    public ElasticsearchTransport transport(RestClient restClient) {
        // jackson 2 defaults keep the document format as before the switch to jackson 3,
        // java.time support is built into jackson 3 (no JavaTimeModule needed)
        JsonMapper jsonMapper = JsonMapper.builderWithJackson2Defaults()
                .disable(SerializationFeature.INDENT_OUTPUT)
                .changeDefaultPropertyInclusion(incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        return new RestClientTransport(restClient, new Jackson3JsonpMapper(jsonMapper));
    }

    @Bean
    public ElasticsearchClient client(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }

    @Bean
    ElasticsearchSynonymsClient clientSynonyms(ElasticsearchTransport transport) {
        return new ElasticsearchSynonymsClient(transport);
    }

}
