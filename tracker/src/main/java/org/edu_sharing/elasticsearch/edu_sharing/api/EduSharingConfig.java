package org.edu_sharing.elasticsearch.edu_sharing.api;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.netty.handler.logging.LogLevel;
import org.edu_sharing.elasticsearch.edu_sharing.api.authorization.AuthorizationClient;
import org.edu_sharing.elasticsearch.edu_sharing.api.authorization.AuthorizationFilterFunction;
import org.edu_sharing.elasticsearch.edu_sharing.api.preview.PreviewApi;
import org.edu_sharing.elasticsearch.edu_sharing.api.preview.PreviewDataDecoder;
import org.edu_sharing.generated.repository.backend.services.rest.client.api.*;
import org.edu_sharing.generated.repository.backend.services.rest.client.handler.ApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class EduSharingConfig {

    @Value("${alfresco.protocol}://${alfresco.host}:${alfresco.port}/edu-sharing")
    private String baseUrl;


    @Value("${alfresco.username}")
    private String alfrescoUsername;

    @Value("${alfresco.password}")
    private String alfrescoPassword;

    @Value("${alfresco.readTimeout}")
    private Duration readTimeout;

    @Value("${spring.http.codecs.max-in-memory-size}")
    private DataSize maxInMemorySize;

    @Value("${log.requests:false}")
    private boolean logRequests;

    @Value("${valuespace.cache.check.after.ms: 120000}")
    private long mdsCacheExpireAfter;

    @Bean
    public CacheManager mdsCacheManager() {
        CaffeineCacheManager caffeineCacheManager = new CaffeineCacheManager();
        caffeineCacheManager.setCaffeine(
                Caffeine.newBuilder()
                        .expireAfterWrite(mdsCacheExpireAfter, TimeUnit.MILLISECONDS));
        return caffeineCacheManager;
    }


    @Bean
    public WebClient webClient() {

        HttpClient httpClient = HttpClient.create()
                .followRedirect(true)
                .responseTimeout(Duration.ofSeconds(readTimeout.toSeconds()));

        if (logRequests) {
            httpClient.wiretap("reactor.netty.client.HttpClient", LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL); // optional detailed logging
        }

        AuthorizationFilterFunction auth = new AuthorizationFilterFunction(new AuthorizationClient(baseUrl + "/rest", alfrescoUsername, alfrescoPassword));

        return WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(clientDefaultCodecsConfigurer -> clientDefaultCodecsConfigurer.customCodecs().register(new PreviewDataDecoder()))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(it -> it.defaultCodecs().maxInMemorySize((int) maxInMemorySize.toBytes()))
                .filter(auth)
                .build();
    }


    @Bean
    public ApiClient apiClient(WebClient webClient) {
        String restUrl = baseUrl+"/rest";
        ApiClient apiClient = new ApiClient(webClient);
        apiClient.setBasePath(restUrl);
        return apiClient;
    }

    @Bean
    public PreviewApi previewApi(WebClient webClient) {
        return new PreviewApi(webClient);
    }

    @Bean
    public NetworkV1Api networkV1Api(ApiClient apiClient) {
        return new NetworkV1Api(apiClient);
    }

    @Bean
    public StatisticV1Api statisticV1Api(ApiClient apiClient) {
        return new StatisticV1Api(apiClient);
    }

    @Bean
    public MdsV1Api mdsV1Api(ApiClient apiClient) {
        return new MdsV1Api(apiClient);
    }

    @Bean
    public AboutApi aboutApi(ApiClient apiClient) {
        return new AboutApi(apiClient);
    }

    @Bean
    public NodeV1Api nodeV1Api(ApiClient apiClient) {
        return new NodeV1Api(apiClient);
    }

    @Bean
    public TrackingV1Api trackingV1Api(ApiClient apiClient){
        return new TrackingV1Api(apiClient);
    }

    @Bean
    public SharingV1Api sharingV1Api(ApiClient apiClient){
        return new SharingV1Api(apiClient);
    }

    @Bean
    public RelationV1Api relationV1Api(ApiClient apiClient){
        return new RelationV1Api(apiClient);
    }

    @Bean
    public SuggestionsV1Api suggestionsV1Api(ApiClient apiClient){
        return new SuggestionsV1Api(apiClient);
    }
}
