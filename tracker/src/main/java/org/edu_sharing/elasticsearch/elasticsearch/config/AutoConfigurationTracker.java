package org.edu_sharing.elasticsearch.elasticsearch.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.mapping.DynamicTemplate;
import co.elastic.clients.elasticsearch._types.mapping.MatchType;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.IndexSettings;
import co.elastic.clients.elasticsearch.indices.IndexSettingsAnalysis;
import co.elastic.clients.elasticsearch.synonyms.ElasticsearchSynonymsClient;
import co.elastic.clients.util.NamedValue;
import co.elastic.clients.util.ObjectBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.edu_sharing.elasticsearch.elasticsearch.core.AdminService;
import org.edu_sharing.elasticsearch.elasticsearch.core.AdminServiceSynonyms;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationInfo;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.AppInfo;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@AutoConfiguration
public class AutoConfigurationTracker {


    @Value("${elastic.index.number_of_shards}")
    private int indexNumberOfShards;

    @Value("${elastic.index.number_of_replicas}")
    private int indexNumberOfReplicas;

    @Value("${elastic.index.max_regex_length}")
    private int indexMaxRegexLength;

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
    public IndexConfiguration migrationsIndex() {
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
    @ConditionalOnMissingBean(name = "trackerState")
    public IndexConfiguration trackerState() {
        return new IndexConfiguration(req -> req
                .index("transactions_" + version)
                .settings(s -> s.index(id -> id
                        .numberOfShards(Integer.toString(indexNumberOfShards))
                        .numberOfReplicas(Integer.toString(indexNumberOfReplicas)))));
    }

    /**
     * Dead letter index: nodes that could not be indexed for a reason that will not go away by
     * retrying. Written by {@code NodeFailureService}, emptied again as soon as a node is resolved,
     * so its document count is exactly the number of currently broken nodes.
     * <p>
     * {@code dynamic: strict} on purpose - an index that exists to report broken mappings must not
     * be able to break its own mapping.
     */
    @Bean
    @ConditionalOnMissingBean(name = "nodeFailures")
    public IndexConfiguration nodeFailures() {
        return new IndexConfiguration(req -> req
                .index("tracker_failures_" + version)
                .settings(s -> s.index(id -> id
                        .numberOfShards(Integer.toString(indexNumberOfShards))
                        .numberOfReplicas(Integer.toString(indexNumberOfReplicas))))
                .mappings(m -> m
                        .dynamic(DynamicMapping.Strict)
                        .properties("tracker", p -> p.keyword(k -> k))
                        .properties("source", p -> p.keyword(k -> k))
                        .properties("operation", p -> p.keyword(k -> k))
                        .properties("nodeRef", p -> p.keyword(k -> k))
                        .properties("nodeType", p -> p.keyword(k -> k))
                        .properties("dbid", p -> p.long_(l -> l))
                        .properties("txnId", p -> p.long_(l -> l))
                        .properties("errorType", p -> p.keyword(k -> k))
                        .properties("errorReason", p -> p.text(t -> t))
                        .properties("firstSeen", p -> p.date(d -> d))
                        .properties("lastSeen", p -> p.date(d -> d))
                        .properties("attempts", p -> p.long_(l -> l))));
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
                .maxRegexLength(indexMaxRegexLength)
                .mapping(mapping -> mapping.totalFields(tf -> tf.limit("20000")))
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
                                        .minShingleSize(2)
                                        .maxShingleSize(3))));

        return builder;

    }

    /**
     * get mappings for the index
     *
     * @param mapping                the builder to use
     * @param unlimitedKeywordFields list of fields for which ignore_above is removed in the keyword section
     */
    ObjectBuilder<TypeMapping> getWorkspaceMappings(TypeMapping.Builder mapping, List<String> unlimitedKeywordFields) {
        List<NamedValue<DynamicTemplate>> templates = new java.util.ArrayList<>(List.of(
                new NamedValue<>("aggregated_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("string")
                        .pathMatch("properties_aggregated.*")
                        .mapping(mp -> mp.keyword(kw -> kw.ignoreAbove(256).store(true))))),

                new NamedValue<>("nodeRef_type", DynamicTemplate.of(dt -> dt
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

                new NamedValue<>("owner_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*owner$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                new NamedValue<>("path_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*path$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                new NamedValue<>("fullpath_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*fullpath$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                new NamedValue<>("fullpaths_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*fullpaths$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                new NamedValue<>("fulldisplaypath_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*fulldisplaypath$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                new NamedValue<>("aspects_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*aspects$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                new NamedValue<>("permissions_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*permissions.(\\w*.)*$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                new NamedValue<>("type_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*type$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                new NamedValue<>("content_type", DynamicTemplate.of(dt -> dt
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

                new NamedValue<>("properties_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*properties.(ccm:original|cclom:location|sys:node-uuid|cclom:format|cm:versionLabel)$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                new NamedValue<>("title_type", DynamicTemplate.of(dt -> dt
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

                new NamedValue<>("workflow_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .pathMatch("workflow.*")
                        .mapping(mp -> mp.keyword(v -> v)))),

                new NamedValue<>("contributor_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^contributor.(email|firstname|lastname|org|url|uuid|vcard)$")
                        .mapping(mp -> mp.keyword(v -> v)))),

                new NamedValue<>("long_type", DynamicTemplate.of(dt -> dt
                        .matchMappingType("*")
                        .matchPattern(MatchType.Regex)
                        .pathMatch("^(?:\\w+\\.)*(aclId|txnId|dbid)$")
                        .mapping(mp -> mp.long_(v -> v)))),

                new NamedValue<>("convert_date", DynamicTemplate.of(dt -> dt
                        .matchMappingType("date")
                        .pathMatch("*properties.*")
                        .mapping(mp -> mp.text(t -> t
                                .store(true)
                                .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                .fields("date", f -> f.date(v -> v.ignoreMalformed(true))))))),

                new NamedValue<>("i18n_fields", DynamicTemplate.of(dt -> dt
                        .matchMappingType("string", "long", "double", "boolean", "date")
                        .pathMatch("i18n.*")
                        .mapping(mp -> mp.keyword(t -> t
                                        .fields("sort", f -> f.keyword(t2 -> t2.normalizer("lowercase")))
                                )
                        ))),

                new NamedValue<>("convert_numeric_long", DynamicTemplate.of(dt -> dt
                        .matchMappingType("long")
                        .pathMatch("*properties.*")
                        .mapping(mp -> mp.text(t -> t
                                .store(true)
                                .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                .fields("number", f -> f.long_(v -> v.ignoreMalformed(true))))))),

                new NamedValue<>("convert_numeric_double", DynamicTemplate.of(dt -> dt
                        .matchMappingType("double")
                        .pathMatch("*properties.*")
                        .mapping(mp -> mp.text(t -> t
                                .store(true)
                                .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                .fields("number", f -> f.float_(v -> v.ignoreMalformed(true))))))),

                new NamedValue<>("generate_sort_lowercase", DynamicTemplate.of(dt -> dt
                        .matchMappingType("string")
                        .pathMatch("*properties.*")
                        .mapping(mp -> mp.text(t -> t
                                        .store(true)
                                        .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                        .fields("sort", f2 -> f2.keyword(kw2 -> kw2.ignoreAbove(256).normalizer("lowercase")))
                                )
                        ))),
                new NamedValue<>("copy_facettes", DynamicTemplate.of(dt -> dt
                        .matchMappingType("string")
                        .pathMatch("*properties.*")
                        .mapping(mp -> mp.text(t -> t
                                        .store(true)
                                        .copyTo("properties_aggregated.{name}")
                                        .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                )
                        ))),
                new NamedValue<>("convert_date_aggregated", DynamicTemplate.of(dt -> dt
                        .matchMappingType("date")
                        .pathMatch("*properties_aggregated.*")
                        .mapping(mp -> mp.text(t -> t
                                .store(true)
                                .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                .fields("date", f -> f.date(v -> v.ignoreMalformed(true))))))),

                new NamedValue<>("convert_numeric_long_aggregated", DynamicTemplate.of(dt -> dt
                        .matchMappingType("long")
                        .pathMatch("*properties_aggregated.*")
                        .mapping(mp -> mp.text(t -> t
                                .store(true)
                                .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                .fields("number", f -> f.long_(v -> v.ignoreMalformed(true))))))),

                new NamedValue<>("convert_numeric_double_aggregated", DynamicTemplate.of(dt -> dt
                        .matchMappingType("double")
                        .pathMatch("*properties_aggregated.*")
                        .mapping(mp -> mp.text(t -> t
                                .store(true)
                                .fields("keyword", f -> f.keyword(kw -> kw.ignoreAbove(256)))
                                .fields("number", f -> f.float_(v -> v.ignoreMalformed(true))))))),

                new NamedValue<>("statistics_rating", DynamicTemplate.of(dt -> dt
                        .pathMatch("statistic_RATING_*")
                        .mapping(mp -> mp.float_(f -> f)))),

                new NamedValue<>("statistics_generic", DynamicTemplate.of(dt -> dt
                        .pathMatch("statistic_*")
                        .mapping(mp -> mp.long_(l -> l))))
        ));
        if (unlimitedKeywordFields != null && !unlimitedKeywordFields.isEmpty()) {
            templates.add(0,
                    new NamedValue<>("unlimited_keyword_length_type", DynamicTemplate.of(dt -> dt
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
                                .properties("small", prop -> prop.binary(v -> v))))
                .properties("userEvent", ueProp -> ueProp
                        .object(ueObject -> ueObject
                                .properties("nodeId", prop -> prop.keyword(v -> v))
                                .properties("initiator", prop -> prop.keyword(v -> v))
                                .properties("receiver", prop -> prop.keyword(v -> v))
                                .properties("type", prop -> prop.keyword(v -> v))
                                // TODO planned for the next index iteration; also adapt queries as well!
                                //.properties("occurredAt", prop -> prop.date(v -> v))
                                .properties("timestamp", prop -> prop.date(v -> v))))
                .properties("share", shareProp -> shareProp
                        .object(shareObj -> shareObj
                                .properties("id", prop -> prop.long_(v -> v))
                                .properties("nodeId", prop -> prop.keyword(v -> v))
                                .properties("sharedBy", prop -> prop.keyword(v -> v))
                                .properties("sharedWith", prop -> prop.keyword(v -> v))
                                .properties("shareStatus", prop -> prop.keyword(v -> v))
                                .properties("shareType", prop -> prop.keyword(v -> v))
                                .properties("timestamp", prop -> prop.date(v -> v))))
                .properties("join_children", joinChildrenProp -> joinChildrenProp
                        .join(join -> join
                                .relations("node", List.of("userEvent", "share"))
                        ))
                .properties("relations", relationProp -> relationProp
                        .object(relObj -> relObj
                                .properties("fromNode", prop -> prop.keyword(v -> v))
                                .properties("toNode", prop -> prop.keyword(v -> v))
                                .properties("type", prop -> prop.keyword(v -> v))
                                .properties("createdBy", prop -> prop.keyword(v -> v))
                                .properties("created", prop -> prop.date(v -> v))
                                .properties("modifiedBy", prop -> prop.keyword(v -> v))
                                .properties("modified", prop -> prop.date(v -> v))
                                .properties("evaluation", evalProp -> evalProp
                                        .object(evalObj -> evalObj
                                                .properties("approvedBy", prop -> prop.keyword(v -> v))
                                        ))
                        ))
                .properties("suggestions", relationProp -> relationProp
                        .nested(nested -> nested
                                .properties("createdBy", prop -> prop.keyword(v -> v))
                                .properties("created", prop -> prop.date(v -> v))
                                .properties("description", prop -> prop.keyword(v -> v))
                                .properties("id", prop -> prop.keyword(v -> v))
                                .properties("type", prop -> prop.keyword(v -> v))
                                .properties("nodeId", prop -> prop.keyword(v -> v))
                                .properties("propertyId", prop -> prop.keyword(v -> v))
                                .properties("value", prop -> prop.keyword(v -> v))
                                .properties("version", prop -> prop.keyword(v -> v))
                                .properties("status", prop -> prop.keyword(v -> v))
                        ))
                .properties("extendedData", prop -> prop.nested(nested -> nested));
    }


    @Bean
    public StatusIndexService<AppInfo> appInfoStatusService(ElasticsearchClient client, IndexConfiguration appInfo) {
        return new StatusIndexService<>(appInfo.getIndex(), client, AppInfo::new, "0", AppInfo.class);
    }
}
