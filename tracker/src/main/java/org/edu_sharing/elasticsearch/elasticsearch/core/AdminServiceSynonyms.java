package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.analysis.SynonymFormat;
import co.elastic.clients.elasticsearch.indices.*;
import co.elastic.clients.elasticsearch.synonyms.ElasticsearchSynonymsClient;
import co.elastic.clients.elasticsearch.synonyms.GetSynonymsSetsResponse;
import co.elastic.clients.elasticsearch.synonyms.SynonymRule;
import co.elastic.clients.elasticsearch.synonyms.get_synonyms_sets.SynonymsSetItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.edu_sharing.elasticsearch.tracker.utils.Partition;
import org.edu_sharing.repository.client.tools.CCConstants;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
public class AdminServiceSynonyms {

    private final ElasticsearchClient client;
    private final ElasticsearchSynonymsClient synonymsClient;
    private final IndexConfiguration workspace;

    @PostConstruct
    private void init() throws IOException{
        putSynonymsSet();
    }

    private void putSynonymsSet() throws IOException {
        //InputStream is = getClass().getClassLoader().getResourceAsStream("synonyms.txt");

        Path pathSynFile = Paths.get("synonyms/synonyms.txt");
        Path pathSynHashFile = Paths.get("synonyms/synonymsHash.txt");

        if(!Files.exists(pathSynFile)){
            log.info("No file synonyms/synonyms.txt found");
            return;
        }

        String currentFileHash = getFileHash(pathSynFile);
        String lastFileHash = Files.exists(pathSynHashFile) ? Files.readString(pathSynHashFile) : null;

        if(currentFileHash == null || currentFileHash.equals(lastFileHash)){
            log.info("Synonyms identical. No update required");
            return;
        }

        List<SynonymRule> rules = new ArrayList<>();
        try (Stream<String> stream = Files.lines(pathSynFile)) {
            stream.forEach(s -> rules.add(new SynonymRule.Builder().synonyms(s).build()));
        } catch (IOException e) {
            //@TODO
            log.error(e.getMessage(), e);
        }

        // create an own synonymset for every 10000 entrie
        Collection<List<SynonymRule>> partitions = Partition.getPartitions(rules, 10000);


        try {
            int suffixId = 1;
            for(List<SynonymRule> r : partitions){
                String suffix = (suffixId == 1) ? "" : "_"+suffixId;
                log.info("put {} synonyms ", r.size());
                synonymsClient.putSynonym(syn -> syn
                        .id(CCConstants.ELASTICSEARCH_SYNONYMSET_PREFIX + suffix)
                        .synonymsSet(r));
                suffixId++;
            }
            Files.writeString(pathSynHashFile,currentFileHash);
        }catch (co.elastic.clients.transport.TransportException e ){
            log.error(e.getMessage(), e);
            Files.writeString(pathSynHashFile,currentFileHash);
            //known elastic bug deserializing response
            //https://github.com/elastic/elasticsearch-java/issues/784
        }
    }

    private String getFileHash(Path pathSynFileName){
        try {
            byte[] data = Files.readAllBytes(pathSynFileName);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            return Base64.encodeBase64String(hash);
        }catch (NoSuchAlgorithmException | IOException e){
            log.error(e.getMessage(), e);
        }
        return null;
    }

    public void updateSynonymSettings() throws IOException {
        GetSynonymsSetsResponse synonymsSets = synonymsClient.getSynonymsSets();
        if(synonymsSets == null || synonymsSets.count() == 0 ){
            log.info("synonym analyzer settings not necessary cause no synonymset provided");
            return;
        }

        GetIndicesSettingsResponse settings = client.indices().getSettings(r -> r.index(workspace.getIndex()));
        if(!hasSynonymAnylzer(settings, workspace.getIndex())){
            log.info("existing workspace index must be updated to add synonym analyzers for recently added synonymset");


            PutIndicesSettingsRequest putIndicesSettingsRequest = PutIndicesSettingsRequest.of(p -> p
                    .index(workspace.getIndex())
                    .settings(b -> {
                        b
                                .analysis(ba -> {
                                    try {
                                        addSynonymsAnalyzer(ba);
                                    } catch (IOException e) {
                                        throw new RuntimeException(e);
                                    }
                                    return ba;
                                });
                        return b;
                    }));

            client.indices().close(CloseIndexRequest.of(c -> c.index(workspace.getIndex())));
            client.indices().putSettings(putIndicesSettingsRequest);
            OpenResponse open = client.indices().open(OpenRequest.of(o -> o.index(workspace.getIndex())));
            if(!open.acknowledged() || !open.shardsAcknowledged()){
                log.error("failed to open index:{} after updating synonyms. resp:{}", workspace.getIndex(), open);
            }
        }
    }

    private static Boolean hasSynonymAnylzer(GetIndicesSettingsResponse settings, String index) {
        return Optional.ofNullable(settings)
                .map(x -> x.get(index))
                .map(IndexState::settings)
                .map(IndexSettings::index)
                .map(IndexSettings::analysis)
                .map(IndexSettingsAnalysis::analyzer)
                .map(Map::keySet)
                .map(x -> x.contains(CCConstants.ELASTICSEARCH_ANALYZER_PREFIX))
                .orElse(false);
    }

    private void addSynonymsAnalyzer(IndexSettingsAnalysis.Builder builder) throws IOException {
        GetSynonymsSetsResponse synonymsSets = synonymsClient.getSynonymsSets();
        int suffixId = 1;
        for(SynonymsSetItem item: synonymsSets.results()){
            String suffix = (suffixId == 1) ? "" : "_"+suffixId;
            builder.analyzer(CCConstants.ELASTICSEARCH_ANALYZER_PREFIX + suffix, a -> a
                            .custom(c -> c
                                    .tokenizer("standard")
                                    .filter("lowercase")
                                    .filter("synonym_graph" + suffix)))
                    .filter("synonym_graph", f -> f.definition(def -> def
                            .synonym(syn -> syn
                                    .format(SynonymFormat.Solr)
                                    .synonymsSet(item.synonymsSet())
                                    .updateable(true))));
            suffixId++;
        }
    }
}
