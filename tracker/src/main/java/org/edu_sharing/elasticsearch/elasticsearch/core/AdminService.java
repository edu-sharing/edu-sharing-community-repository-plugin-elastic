package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.HealthStatus;
import co.elastic.clients.elasticsearch.cluster.HealthResponse;
import co.elastic.clients.elasticsearch.cluster.health.IndexHealthStats;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class AdminService {  //, SmartInitializingSingleton {

    private final ElasticsearchClient client;
    private final Collection<IndexConfiguration> indexConfigurations;
    private final AdminServiceSynonyms adminServiceSynonyms;

    @Setter
    private boolean autocreateIndex = true;

    public boolean createIndex(Collection<IndexConfiguration> indexConfigurations) throws IOException {
        boolean createdAnyIndex = false;
        for (IndexConfiguration indexConfiguration : indexConfigurations) {
            try {
                if (!client.indices().exists(req -> req.index(indexConfiguration.getIndex())).value()) {
                    client.indices().create(indexConfiguration.getCreateIndexRequest());
                    createdAnyIndex = true;
                }
            } catch (IOException ex) {
                log.error("create index {} failed with {}", indexConfiguration.getIndex(), ex.getMessage(), ex);

                throw ex;
            }
        }
        return createdAnyIndex;
    }

    public boolean createIndex(IndexConfiguration indexConfiguration, IndexConfiguration... indexConfigurations) throws IOException {
        List<IndexConfiguration> list = new ArrayList<>();
        list.add(indexConfiguration);
        list.addAll(List.of(indexConfigurations));
        return createIndex(list);
    }

    public void deleteIndex(IndexConfiguration indexConfiguration, IndexConfiguration... indexConfigurations) throws IOException {
        List<String> indices = new ArrayList<>();
        indices.add(indexConfiguration.getIndex());
        indices.addAll(Arrays.stream(indexConfigurations).map(IndexConfiguration::getIndex).collect(Collectors.toList()));

        try {
            client.indices().delete(req -> req.index(indices));
        } catch (IOException ex) {
            log.error("delete index [{}] failed with {}", String.join(", ", indices), ex.getMessage(), ex);
            throw ex;
        }
    }

    public boolean indecesConfiguredExist() throws IOException{
        for(IndexConfiguration indexConfiguration : indexConfigurations){
            if(!indicesExists(indexConfiguration.getIndex())){
                log.info("index does not exist "+ indexConfiguration.getIndex());
                return false;
            }
        }
        return true;
    }

    public boolean indicesExists(String value, String... values) throws IOException {
        boolean result = client.indices().exists(req -> req.index(value, values)).value();
        if(result){

            HealthResponse response = client.cluster().health(h -> h
                    .index(value,values));
            result = response.indices().entrySet().stream()
                    .allMatch( e -> e.getValue().status().equals(HealthStatus.Green));
            if(!result){
                log.info("one of indexes does not have health status green {} {}",value,Arrays.toString(values));
            }
        }
        return result;
    }

    @PostConstruct
    public void init() {
        if (!autocreateIndex) {
            return;
        }

        try {
            createIndex(indexConfigurations);
            adminServiceSynonyms.updateSynonymSettings();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
