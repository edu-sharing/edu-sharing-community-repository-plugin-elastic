package org.edu_sharing.elasticsearch.elasticsearch.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.mapping.DynamicTemplate;
import co.elastic.clients.elasticsearch._types.mapping.MatchType;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.elasticsearch.indices.IndexSettingsAnalysis;
import co.elastic.clients.elasticsearch.synonyms.ElasticsearchSynonymsClient;
import co.elastic.clients.util.ObjectBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.edu_sharing.elasticsearch.elasticsearch.core.*;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationInfo;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.AclTx;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.AppInfo;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.StatisticTimestamp;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.tracker.TrackerServiceFactory;
import org.edu_sharing.elasticsearch.tracker.TransactionTracker;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@AutoConfiguration
public class AutoConfigurationTracker {


    @Value("${elastic.index.number_of_shards}")
    private int indexNumberOfShards;

    @Value("${elastic.index.number_of_replicas}")
    private int indexNumberOfReplicas;

    private final String version;

    public AutoConfigurationTracker(List<MigrationInfo> migrationInfos) {
        // Migration information is sorted, with the latest version being the last item in the list
        version = migrationInfos.get(migrationInfos.size() - 1).getVersion();
    }


    @Bean
    @ConditionalOnMissingBean(AdminService.class)
    public AdminService adminService(ElasticsearchClient client, Collection<IndexConfiguration> indexConfigurations, AdminServiceSynonyms adminServiceSynonyms) {
        return new AdminService(client, indexConfigurations, adminServiceSynonyms);
    }

    @Bean
    @ConditionalOnMissingBean(AdminServiceSynonyms.class)
    public AdminServiceSynonyms adminServiceSynonyms(ElasticsearchClient client, ElasticsearchSynonymsClient clientSynonyms, IndexConfiguration workspace) {
        return new AdminServiceSynonyms(client, clientSynonyms, workspace);
    }

    @Bean
    public IndexConfiguration appInfo() {
        return new IndexConfiguration(req -> req
                .index("app_info")
                .settings(s -> s.index(id -> id
                        .numberOfShards(Integer.toString(indexNumberOfShards))
                        .numberOfReplicas(Integer.toString(indexNumberOfReplicas)))));
    }

    @Bean
    public IndexConfiguration migrationIndex() {
        return new IndexConfiguration(req -> req
                .index("migrations")
                .settings(s -> s.index(id -> id
                        .numberOfShards(Integer.toString(indexNumberOfShards))
                        .numberOfReplicas(Integer.toString(indexNumberOfReplicas)))));
    }

    @Bean
    @ConditionalOnMissingBean(name = "workspace")
    public IndexConfiguration workspace() {
        return new IndexConfiguration(req -> req
                .index("workspace_" + version)
                .settings(this::getWorkspaceIndexSettings)
                .mappings(m -> getWorkspaceMappings(m, null)));
    }

    @Bean
    @ConditionalOnMissingBean(name = "transactions")
    public IndexConfiguration transactions() {
        return new IndexConfiguration(req -> req
                .index("transactions_" + version)
                .settings(s -> s.index(id -> id
                        .numberOfShards(Integer.toString(indexNumberOfShards))
                        .numberOfReplicas(Integer.toString(indexNumberOfReplicas)))));
    }

    @Bean
    @ConditionalOnMissingBean(name = "authorities")
    public IndexConfiguration authorities() {
        return new IndexConfiguration(req -> req
                .index("authorities_" + version)
                .settings(this::getWorkspaceIndexSettings)
                .mappings(m -> getWorkspaceMappings(m, Stream.of(
                                        // no length limitation for the following keyword fields
                                        CCConstants.CM_PROP_AUTHORITY_NAME,
                                        CCConstants.CM_PROP_AUTHORITY_AUTHORITYDISPLAYNAME,
                                        CCConstants.CCM_PROP_GROUPEXTENSION_GROUPEMAIL,
                                        CCConstants.PROP_USER_FIRSTNAME,
                                        CCConstants.PROP_USER_LASTNAME,
                                        CCConstants.PROP_USER_EMAIL
                                ).map(CCConstants::getValidLocalName).collect(Collectors.toList())
                        )
                )
        );
    }


    @Bean
    @ConditionalOnMissingBean(name = "migrations")
    public IndexConfiguration migrations() {
        return new IndexConfiguration(req -> req
                .index("migrations")
                .settings(s -> s.index(id -> id
                        .numberOfShards(Integer.toString(indexNumberOfShards))
                        .numberOfReplicas(Integer.toString(indexNumberOfReplicas)))));
    }

    IndexSettings.Builder getWorkspaceIndexSettings(IndexSettings.Builder s) {
        return s.index(id -> id
                        .numberOfShards(Integer.toString(indexNumberOfShards))
                        .numberOfReplicas(Integer.toString(indexNumberOfReplicas)))
                .mapping(mapping -> mapping.totalFields(tf -> tf.limit(10000L)))
                .analysis(this::getIndexSettingAnalysis);
    }

    private IndexSettingsAnalysis.Builder getIndexSettingAnalysis(IndexSettingsAnalysis.Builder builder) {
        builder
                .analyzer("trigram", a -> a
                        .custom(c -> c
                                .tokenizer("standard")
                                .filter("lowercase", "shingle")))
                .analyzer("reverse", a -> a
                        .custom(c -> c
                                .tokenizer("standard")
                                .filter("lowercase", "reverse")))
                .filter("shingle", f -> f
                        .definition(def -> def
                                .shingle(shingle -> shingle
                                        .minShingleSize("2")
                                        .maxShingleSize("3"))));

        return builder;

    }

    /**
     * get mappings for the index
     * @param mapping the builder to use
     * @param unlimitedKeywordFields list of fields for which ignore_above is removed in the keyword section
     */
    ObjectBuilder<TypeMapping> getWorkspaceMappings(TypeMapping.Builder mapping, List<String> unlimitedKeywordFields) {
        List<Map<String, DynamicTemplate>> templates = new java.util.ArrayList<>(List.of(
                Map.of("aggregated_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("string")
                        .pathMatch("properties_aggregated.*")
                        .mapping(mp -> mp.keyword(kw -> kw.ignoreAbove(256).store(true))))),

                Map.of("nodeRef_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("object")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*(nodeRef|parentRef)$")
                        .mapping(mp -> mp
                                .object(nodeRefObj -> nodeRefObj
                                        .properties("id", storeRefProp -> storeRefProp.keyword(v -> v))
                                        .properties("storeRef", storeRefProp -> storeRefProp
                                                .object(storeRefObj -> storeRefObj
                                                        .properties("protocol", protProp -> protProp.keyword(v -> v))
                                                        .properties("identifier", idProp -> idProp.keyword(v -> v)))))))),

                Map.of("owner_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*owner$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                Map.of("path_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*path$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                Map.of("fullpath_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*fullpath$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                Map.of("fullpaths_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*fullpaths$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                Map.of("fulldisplaypath_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*fulldisplaypath$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                Map.of("aspects_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*aspects$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                Map.of("permissions_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*permissions.(\\w*.)*$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                Map.of("type_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*type$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                Map.of("content_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*content$")
                        .mapping(mp -> mp
                                .object(obj -> obj
                                        .properties("fulltext", prop -> prop.text(v -> v))
                                        .properties("contentId", prop -> prop.long_(v -> v))
                                        .properties("size", prop -> prop.long_(v -> v))
                                        .properties("encoding", prop -> prop.keyword(v -> v))
                                        .properties("locale", prop -> prop.keyword(v -> v))
                                        .properties("mimetype", prop -> prop.keyword(v -> v)))))),

                Map.of("properties_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*properties.(ccm:original|cclom:location|sys:node-uuid|cclom:format|cm:versionLabel)$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                Map.of("title_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*properties.(cclom:title)$")
                        .mapping(mp -> mp.text(t -> t
                                .store(true)
                                .copyTo("properties_aggregated.cclom:title")
                                .fields("keyword", prop -> prop.keyword(v -> v.ignoreAbove(256)))
                                .fields("sort", prop -> prop.keyword(v -> v.normalizer("lowercase")))
                                .fields("trigram", prop -> prop.text(v -> v.analyzer("trigram")))
                                .fields("reverse", prop -> prop.text(v -> v.analyzer("reverse"))))))),

                Map.of("workflow_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .pathMatch("workflow.*")
                        .mapping(mp -> mp.keyword(v -> v)))),

                Map.of("contributor_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^contributor.(email|firstname|lastname|org|url|uuid|vcard)$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                Map.of("long_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*(aclId|txnId|dbid)$")
                        .mapping(mp -> mp.long_(v -> v)))),

                Map.of("convert_date", DynamicTemplate.of(dt -> dt
                        .matchMappingType("date")
                        .pathMatch("*properties.*")
                        .mapping(mp -> mp.text(t -> t
                                .store(true)
                                .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                .fields("date", f -> f.date(v -> v.ignoreMalformed(true))))))),

                Map.of("i18n_fields", DynamicTemplate.of(dt -> dt
                        .matchMappingType("string", "long", "double", "boolean", "date")
                        .pathMatch("i18n.*")
                        .mapping(mp -> mp.keyword(t -> t
                                        .fields("sort", f -> f.keyword(t2 -> t2.normalizer("lowercase")))
                                )
                        ))),

                Map.of("convert_numeric_long", DynamicTemplate.of(dt -> dt
                        .matchMappingType("long")
                        .pathMatch("*properties.*")
                        .mapping(mp -> mp.text(t -> t
                                .store(true)
                                .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                .fields("number", f -> f.long_(v -> v.ignoreMalformed(true))))))),

                Map.of("convert_numeric_double", DynamicTemplate.of(dt -> dt
                        .matchMappingType("double")
                        .pathMatch("*properties.*")
                        .mapping(mp -> mp.text(t -> t
                                .store(true)
                                .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                .fields("number", f -> f.float_(v -> v.ignoreMalformed(true))))))),

                Map.of("generate_sort_lowercase", DynamicTemplate.of(dt -> dt
                        .matchMappingType("string")
                        .pathMatch("*properties.*")
                        .mapping(mp -> mp.text(t -> t
                                        .store(true)
                                        .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                        .fields("sort", f2 -> f2.keyword(kw2 -> kw2.ignoreAbove(256).normalizer("lowercase")))
                                )
                        ))),
                Map.of("copy_facettes", DynamicTemplate.of(dt -> dt
                        .matchMappingType("string")
                        .pathMatch("*properties.*")
                        .mapping(mp -> mp.text(t -> t
                                        .store(true)
                                        .copyTo("properties_aggregated.{name}")
                                        .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                )
                        ))),
                Map.of("convert_date_aggregated", DynamicTemplate.of(dt -> dt
                        .matchMappingType("date")
                        .pathMatch("*properties_aggregated.*")
                        .mapping(mp -> mp.text(t -> t
                                .store(true)
                                .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                .fields("date", f -> f.date(v -> v.ignoreMalformed(true))))))),

                Map.of("convert_numeric_long_aggregated", DynamicTemplate.of(dt -> dt
                        .matchMappingType("long")
                        .pathMatch("*properties_aggregated.*")
                        .mapping(mp -> mp.text(t -> t
                                .store(true)
                                .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                .fields("number", f -> f.long_(v -> v.ignoreMalformed(true))))))),

                Map.of("convert_numeric_double_aggregated", DynamicTemplate.of(dt -> dt
                        .matchMappingType("double")
                        .pathMatch("*properties_aggregated.*")
                        .mapping(mp -> mp.text(t -> t
                                .store(true)
                                .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                .fields("number", f -> f.float_(v -> v.ignoreMalformed(true))))))),

                Map.of("statistics_rating", DynamicTemplate.of(dt -> dt
                        .pathMatch("statistic_RATING_*")
                        .mapping(mp -> mp.float_(f -> f)))),

                Map.of("statistics_generic", DynamicTemplate.of(dt -> dt
                        .pathMatch("statistic_*")
                        .mapping(mp -> mp.long_(l -> l))))
        ));
        if (unlimitedKeywordFields != null && !unlimitedKeywordFields.isEmpty()) {
            templates.add(0,
                    Map.of("unlimited_keyword_length_type", DynamicTemplate.of(dt -> dt
                                    .matchMappingType("*")
                                    .matchPattern(MatchType.Regex)
                                    .pathMatch("^(?:\\w+\\.)*properties.(" + StringUtils.join(unlimitedKeywordFields, "|") + ")$")
                                    .mapping(mp -> mp.text(t -> t
                                                    .store(true)
                                                    .fields("keyword", prop -> prop.keyword(v -> v))
                                                    .fields("sort", prop -> prop.keyword(v -> v.normalizer("lowercase")))
                                            )
                                    )
                            )
                    )
            );
        }

        return mapping.dynamic(DynamicMapping.True)
                .numericDetection(true)
                .dynamicTemplates(templates)
                .properties("workflow", workProp -> workProp
                        .nested(nt -> nt
                                .properties("comment", prop -> prop.keyword(v -> v))
                                .properties("editor", prop -> prop.keyword(v -> v))
                                .properties("receiver", prop -> prop.keyword(v -> v))
                                .properties("status", prop -> prop.keyword(v -> v))
                                .properties("time", prop -> prop.date(v -> v))
                        ))
                .properties("contributor", prop -> prop.nested(v -> v))
                .properties("children", prop -> prop.nested(v -> v))
                .properties("collections", colProp -> colProp.nested(v -> v))
                .properties("preview", previewProp -> previewProp
                        .object(previewObj -> previewObj
                                .properties("mimetype", prop -> prop.keyword(v -> v))
                                .properties("type", prop -> prop.keyword(v -> v))
                                .properties("icon", prop -> prop.boolean_(v -> v))
                                .properties("small", prop -> prop.binary(v -> v))));
    }


    @Bean
    public StatusIndexService<AppInfo> appInfoStatusService(ElasticsearchClient client, IndexConfiguration appInfo) {
        return new StatusIndexService<>(appInfo.getIndex(), client, AppInfo::new, "0", AppInfo.class);
    }

    @Bean
    @ConditionalOnMissingBean(name = "transactionStateService")
    public StatusIndexService<Tx> transactionStateService(StatusIndexServiceFactory trackerStateServiceFactory, IndexConfiguration transactions) {
        return trackerStateServiceFactory.createTransactionStateService(transactions.getIndex());
    }

    @Bean
    @ConditionalOnMissingBean(name = "aclStateService")
    public StatusIndexService<AclTx> aclStateService(StatusIndexServiceFactory trackerStateServiceFactory, IndexConfiguration transactions) {
        return trackerStateServiceFactory.createAclStateService(transactions.getIndex());
    }

    @Bean
    @ConditionalOnMissingBean(name = "statisticTimestampStateService")
    public StatusIndexService<StatisticTimestamp> statisticTimestampStateService(StatusIndexServiceFactory trackerStateServiceFactory, IndexConfiguration transactions) {
        return trackerStateServiceFactory.createStatisticTimestampStateService(transactions.getIndex());
    }

    @Bean
    @ConditionalOnMissingBean(name = "transactionTracker")
    public TransactionTracker transactionTracker(TrackerServiceFactory trackerServiceFactory, StatusIndexService<Tx> transactionStateService) {
        return trackerServiceFactory.createDefaultTrackerService(transactionStateService);
    }
}
