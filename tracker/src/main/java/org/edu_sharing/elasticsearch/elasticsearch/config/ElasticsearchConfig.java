package org.edu_sharing.elasticsearch.elasticsearch.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.synonyms.ElasticsearchSynonymsClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public RestClient restClient(){
        return  RestClient.builder(new HttpHost(elasticHost, elasticPort, elasticProtocol))
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
    public ElasticsearchTransport transport(RestClient restClient){
        return new RestClientTransport(restClient, new JacksonJsonpMapper());
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
