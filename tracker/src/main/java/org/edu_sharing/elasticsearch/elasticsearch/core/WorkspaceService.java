package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.*;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.ResponseBody;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.util.ObjectBuilder;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.cardme.engine.VCardEngine;
import net.sourceforge.cardme.vcard.VCard;
import net.sourceforge.cardme.vcard.exceptions.VCardParseException;
import net.sourceforge.cardme.vcard.types.ExtendedType;
import net.sourceforge.cardme.vcard.types.NType;
import org.apache.tomcat.util.buf.StringUtils;
import org.edu_sharing.elasticsearch.alfresco.client.*;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeData;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.model.ElasticNode;
import org.edu_sharing.elasticsearch.elasticsearch.utils.DataBuilder;
import org.edu_sharing.elasticsearch.elasticsearch.utils.utils.NodeMetadataSimple;
import org.edu_sharing.elasticsearch.tools.ScriptExecutor;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.cascade.CascadeTracker;
import org.edu_sharing.elasticsearch.tracker.utils.Partition;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.*;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.json.BasicJsonParser;
import org.springframework.boot.json.JsonParseException;
import org.springframework.boot.json.JsonParser;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Serializable;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class WorkspaceService implements SearchHitsRunner {

    public static final String CONTRIBUTOR_REGEX = "ccm:[a-zA-Z]*contributer_[a-zA-Z_-]*";

    @Value("${statistic.historyInDays}")
    int statisticHistoryInDays;

    @Value("${elastic.maxCollectionChildItemsUpdateSize}")
    int maxCollectionChildItemsUpdateSize;

    @Value("${tracker.bulk.size.elastic}")
    int bulkSizeElastic;

    private final SimpleDateFormat statisticDateFormatter = new SimpleDateFormat("yyyy-MM-dd");
    private final String homeRepoId;
    private final ElasticsearchClient client;
    private final ScriptExecutor scriptExecutor;
    private final AtomicInteger nodeCounter = new AtomicInteger(0);
    private final AtomicLong lastNodeCount = new AtomicLong(System.currentTimeMillis());
    private final AlfrescoWebscriptClient alfrescoClient;
    private final String index;

    public WorkspaceService(co.elastic.clients.elasticsearch.ElasticsearchClient client,
                            ScriptExecutor scriptExecutor,
                            EduSharingService eduSharingService,
                            AlfrescoWebscriptClient alfrescoClient,
                            IndexConfiguration workspace) {
        this.client = client;
        this.scriptExecutor = scriptExecutor;
        this.alfrescoClient = alfrescoClient;
        this.index = workspace.getIndex();
        this.homeRepoId = eduSharingService.getHomeRepository().getId();
    }

    public void updateNodesWithAcl(final long aclId, final Map<String, List<String>> permissions) throws IOException {
        log.info("starting: {} ", aclId);

        UpdateByQueryResponse bulkByScrollResponse = client.updateByQuery(req -> req
                .index(index)
                .query(q -> q.term(t -> t.field("aclId").value(aclId)))
                .conflicts(Conflicts.Proceed)
                .refresh(false)
                .script(scr -> scr
                        .source("ctx._source.permissions=params")
                        .params(permissions.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, x -> JsonData.of(x.getValue())))))
        );

        log.info("updated: {}", bulkByScrollResponse.updated());
        List<BulkIndexByScrollFailure> bulkFailures = bulkByScrollResponse.failures();
        for (BulkIndexByScrollFailure failure : bulkFailures) {
            log.error(failure.cause().toString(), failure.cause());
        }
    }

    public void updateNodesWithRelations(final String nodeId, final List<RelationData> relationData) {
        // language=groovy
        final String SCRIPT_UPDATE_RELATIONS = """
                ctx._source.relations = params.relations;
                """;

        UpdateByQueryResponse bulkByScrollResponse;
        try {
            bulkByScrollResponse = client.updateByQuery(req -> req
                    .index(index)
                    .query(q -> q.bool(b -> b
                            .should(s -> s.term(t -> t.field("_id").value(nodeId)))
                            .should(s -> s.bool(b2 -> b2
                                    .must(m -> m.term(t -> t.field("aspects").value(CCConstants.CCM_ASPECT_PUBLISHED)))
                                    .must(m -> m.term(t -> t.field("properties." + CCConstants.getValidLocalName(CCConstants.CCM_PROP_IO_PUBLISHED_ORIGINAL)).value(nodeId)))
                            ))))
                    .conflicts(Conflicts.Proceed)
                    .refresh(true)
                    .script(src -> src
                            .source(SCRIPT_UPDATE_RELATIONS)
                            .params("relations", JsonData.of(relationData)))
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.debug("updated: {}", bulkByScrollResponse.updated());
        List<BulkIndexByScrollFailure> bulkFailures = bulkByScrollResponse.failures();
        for (BulkIndexByScrollFailure failure : bulkFailures) {
            log.error(failure.cause().toString(), failure.cause());
        }
    }

    public UpdateResponse<Void> update(String nodeId, Object data) throws IOException {
        return this.update(req -> req
                .index(index)
                .id(nodeId)
                .doc(data), Void.class);
    }

    private <TDocument, TPartialDocument> UpdateResponse<TDocument> update(Function<
            UpdateRequest.Builder<TDocument, TPartialDocument>,
            ObjectBuilder<UpdateRequest<TDocument, TPartialDocument>>
            > request, Class<TDocument> tDocClass) throws IOException {

        UpdateResponse<TDocument> updateResponse = client.update(request, tDocClass);

        if (Objects.requireNonNull(updateResponse.result()) == Result.Created) {
            log.info("object did not exist");
        }
        return updateResponse;
    }

    public void updateBulk(List<BulkOperation> updateRequests) throws IOException {
        if (updateRequests.isEmpty()) {
            return;
        }

        BulkResponse response = client.bulk(req -> req.index(index).operations(updateRequests));
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null) {
                log.error(item.error().reason());
            }
        }
    }


    public void index(List<BulkOperation> operations) throws IOException {
        if (!operations.isEmpty()) {
            log.info("starting bulk update:");
            BulkResponse bulkResponse = client.bulk(req -> req.index(index).operations(operations));
            log.info("finished bulkBulkOperations:{}", bulkResponse.items().size());
            for (BulkResponseItem item : bulkResponse.items()) {
                if (item.error() != null) {
                    log.error("Failed indexing of {}", item.id());
                    log.error("Failed indexing of {}", item.error().causedBy());
                }
            }
        }
    }

    public void addBulkOperation(NodeData nodeData, List<BulkOperation> operations) throws IOException {
        NodeMetadata node = nodeData.getNodeMetadata();
        // check if a formally moved node was moved again
        if (nodeData.getNodeMetadata().getAspects().contains("sys:cascadeUpdate")) {
            HitsMetadata<ElasticNode> hits = search(QueryBuilders.ids(i -> i.values(Tools.getUUID(nodeData.getNodeMetadata().getNodeRef()))),
                    0,
                    1,
                    null,
                    ElasticNode.class);
            if (hits != null && !hits.hits().isEmpty()) {
                Optional<Long> cascadeTx = Optional.of(hits)
                        .map(HitsMetadata::hits)
                        .map(x -> x.get(0))
                        .map(Hit::source)
                        .map(ElasticNode::getProperties)
                        .map(x -> x.get(CascadeTracker.propCascadeTx))
                        .map(String.class::cast)
                        .map(Long::parseLong);

                if (cascadeTx.isPresent()) {
                    if (cascadeTx.get() < Long.parseLong((String) nodeData.getNodeMetadata().getProperties().get(CCConstants.getValidGlobalName(CascadeTracker.propCascadeTx)))) {
                        nodeData.setRefreshPath(true);
                    }
                }
            }
        }
        DataBuilder builder = new DataBuilder();
        fillData(nodeData, builder);
        Object dataRaw = builder.build();
        Map<String, Object> data = (Map<String, Object>) dataRaw;

        // 2. Script und Parameter dynamisch aufbauen
        StringBuilder scriptSource = new StringBuilder();
        Map<String, JsonData> scriptParams = new HashMap<>();


        data.forEach((key, value) -> {
            if (value == null) {
                scriptSource.append("ctx._source.")
                        .append(key)
                        .append(" = null; ");
            } else {
                // Erzeugt: ctx._source.preview = params.p_preview; ctx._source.properties = params.p_properties; ...
                scriptSource.append("ctx._source.").append(key).append(" = params.p_").append(key).append("; ");
                scriptParams.put("p_" + key, JsonData.of(value));
            }
        });

        List<String> checkForRemove = List.of("contributor", "i18n", "customProperties", "children");
        checkForRemove.forEach(f -> {
            if (!data.containsKey(f)) {
                scriptSource.append("ctx._source.remove('").append(f).append("'); ");
            }
        });

        // 3. BulkOperation mit Upsert zusammenbauen
        BulkOperation bulkOp = BulkOperation.of(b -> b
                .update(u -> u
                        .id(Tools.getUUID(node.getNodeRef()))
                        .action(a -> a
                                .script(s -> s
                                        .source(scriptSource.toString().trim())
                                        .params(scriptParams)
                                )
                                .upsert(JsonData.of(dataRaw)) // Wenn neu, dann das komplette Dokument
                        )
                )
        );
        operations.add(bulkOp);

        if (nodeCounter.addAndGet(1) % 100 == 0) {
            log.info("Processed {} nodes ({}ms per last 100 nodes)", nodeCounter.get(), System.currentTimeMillis() - lastNodeCount.get());
            lastNodeCount.set(System.currentTimeMillis());
        }
    }

    public void fillData(NodeData nodeData, @NonNull DataBuilder builder) throws IOException {
        fillData(nodeData, builder, null);
    }

    public void fillData(NodeData nodeData, @NonNull final DataBuilder builder, String objectName) throws IOException {
        NodeMetadata node = nodeData.getNodeMetadata();
        String storeRefProtocol = Tools.getProtocol(node.getNodeRef());
        String storeRefIdentifier = Tools.getIdentifier(node.getNodeRef());


        if (objectName != null) {
            builder.startObject(objectName);
        } else {
            builder.startObject();
        }
        {
            builder.field("join_children", "node");
            builder.field("aclId", node.getAclId());
            builder.field("txnId", node.getTxnId());
            builder.field("dbid", node.getId());
            if (nodeData.isRefreshPath()) {
                builder.field(CascadeTracker.flag, true);
            }

            List<String> parentUuids = Arrays.asList(node.getPaths().get(0).getApath().split("/"));
            parentUuids.stream().skip(parentUuids.size() - 1).findFirst()
                    .flatMap(parentUuid -> node.getAncestors().stream().filter(s -> s.contains(parentUuid)).findAny())
                    .ifPresent(primaryParentRef -> {
                        //getAncestors() is not sorted
                        builder.startObject("parentRef")
                                .startObject("storeRef")
                                .field("protocol", Tools.getProtocol(primaryParentRef))
                                .field("identifier", Tools.getIdentifier(primaryParentRef))
                                .endObject()
                                .field("id", Tools.getUUID(primaryParentRef))
                                .endObject();
                    });

            String id = node.getNodeRef().split("://")[1].split("/")[1];
            builder.startObject("nodeRef")
                    .startObject("storeRef")
                    .field("protocol", storeRefProtocol)
                    .field("identifier", storeRefIdentifier)
                    .endObject()
                    .field("id", id)
                    .endObject();

            builder.field("owner", node.getOwner());
            builder.field("type", node.getType());
            if (!Objects.equals(objectName, "relation")) {
                scriptExecutor.addCustomPropertiesByScript(builder, nodeData);
            }
            //valuespaces
            if (nodeData.getValueSpaces() != null && !nodeData.getValueSpaces().isEmpty()) {
                builder.startObject("i18n");
                for (Map.Entry<String, Map<String, List<String>>> entry : nodeData.getValueSpaces().entrySet()) {
                    String language = entry.getKey().split("-")[0];
                    builder.startObject(language);
                    for (Map.Entry<String, List<String>> valuespace : entry.getValue().entrySet()) {

                        String key = CCConstants.getValidLocalName(valuespace.getKey());
                        if (key != null) {
                            builder.field(key, valuespace.getValue());
                        } else {
                            builder.field(valuespace.getKey(), valuespace.getValue());
                            // logger.error("unknown valuespace property: " + valuespace.getKey());
                        }
                    }
                    builder.endObject();
                }
                builder.endObject();
            }

            //extendedData
            if (nodeData.getExtendedData() != null && !nodeData.getExtendedData().isEmpty()) {
                builder.field("extendedData", nodeData.getExtendedData());
            }

            if (node.getPaths() != null && !node.getPaths().isEmpty()) {
                addNodePath(builder, node);
            }

            if (nodeData.getReader() != null) {
                builder.startObject("permissions");
                builder.field("read", nodeData.getReader().getReaders());
                for (Map.Entry<String, List<String>> entry : nodeData.getPermissions().entrySet()) {
                    builder.field(entry.getKey(), entry.getValue());
                }
                builder.endObject();
            }

            Map<String, Object> contributorProperties = new HashMap<>();
            builder.startObject("properties");
            for (Map.Entry<String, Serializable> prop : node.getProperties().entrySet()) {

                String key = CCConstants.getValidLocalName(prop.getKey());
                if (key == null) {
                    log.warn("unknown namespace: {}", prop.getKey());
                    continue;
                }

                if (key.matches(CONTRIBUTOR_REGEX)) {
                    if (prop.getValue() != null) {
                        contributorProperties.put(key, prop.getValue());
                    }
                }

                Object value = getValue(prop.getValue(), key);
                if ("cm:modified".equals(key) || "cm:created".equals(key)) {

                    if (prop.getValue() != null && prop.getValue() instanceof String stringValue) {
                        value = Date.from(Instant.parse(stringValue)).getTime();
                    }
                }

                //prevent Elasticsearch exception failed to parse field's value: '1-01-07T01:00:00.000+01:00'
                if ("ccm:replicationmodified".equals(key)) {
                    if (prop.getValue() != null) {
                        value = prop.getValue().toString();
                    }
                }

                //elastic maps this on date field, it gets a  failed to parse field exception when it's empty
                if ("ccm:replicationsourcetimestamp".equals(key)) {
                    if (value != null && value.toString().trim().isEmpty()) {
                        value = null;
                    }
                }

                if ("ccm:mediacenter".equals(key)) {
                    ArrayList<Map<String, Object>> mediacenters = null;
                    if (value != null && !value.toString().trim().isEmpty()) {
                        JsonParser jp = new BasicJsonParser();
                        @SuppressWarnings("unchecked")
                        List<String> mzStatusList = (List<String>) value;
                        ArrayList<Map<String, Object>> result = new ArrayList<>();
                        for (String mzStatus : mzStatusList) {
                            try {
                                result.add(jp.parseMap(mzStatus));
                            } catch (JsonParseException e) {
                                log.warn(e.getMessage());
                            }
                        }
                        if (!result.isEmpty()) {
                            value = result;
                            mediacenters = result;
                        }
                    }

                    if (mediacenters != null) {
                        builder.startObject("ccm:mediacenter_sort");
                        for (Map<String, Object> mediacenter : mediacenters) {
                            builder.startObject((String) mediacenter.get("name"));
                            builder.field("activated", mediacenter.get("activated"));
                            builder.endObject();
                        }
                        builder.endObject();
                    }

                }

                if (value != null) {

                    try {
                        builder.field(key, value);
                    } catch (Exception e) {
                        log.warn("error parsing value field:{}v{}", key, value, e);
                    }
                }
            }
            builder.endObject();

            if (node.getProperties().get(CCConstants.CCM_PROP_WF_PROTOCOL) != null) {
                mapWorkflowProtocol(node.getProperties().get(CCConstants.CCM_PROP_WF_PROTOCOL), builder);
            }

            builder.field("aspects", node.getAspects());

            if (!contributorProperties.isEmpty()) {
                VCardEngine vcardEngine = new VCardEngine();
                builder.startArray("contributor");
                for (Map.Entry<String, Object> entry : contributorProperties.entrySet()) {
                    if (entry.getValue() instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<String> val = (List<String>) entry.getValue();
                        for (String v : val) {
                            try {
                                if (v == null) continue;
                                VCard vcard = vcardEngine.parse(v);
                                if (vcard != null) {

                                    builder.startObject();
                                    builder.field("property", entry.getKey());
                                    if (vcard.getN() != null) {
                                        NType n = vcard.getN();
                                        builder.field("firstname", n.getGivenName());
                                        builder.field("lastname", n.getFamilyName());
                                    }

                                    if (vcard.getEmails() != null && !vcard.getEmails().isEmpty()) {
                                        builder.field("email", vcard.getEmails().get(0).getEmail());
                                    }
                                    if (vcard.getUid() != null) {
                                        builder.field("uuid", vcard.getUid().getUid());
                                    }
                                    if (vcard.getUrls() != null && !vcard.getUrls().isEmpty()) {
                                        builder.field("url", vcard.getUrls().get(0).getRawUrl());
                                    }
                                    if (vcard.getOrg() != null) {
                                        builder.field("org", vcard.getOrg().getOrgName());
                                    }

                                    if (vcard.getN() != null) {
                                        NType n = vcard.getN();
                                        builder.field("displayname", (
                                                (vcard.getTitle() != null && vcard.getTitle().getTitle() != null ? vcard.getTitle().getTitle() : "") + " " +
                                                        (n.getGivenName() != null ? n.getGivenName() : "") + " " +
                                                        (n.getFamilyName() != null ? n.getFamilyName() : "")
                                        ).trim());
                                    } else if (vcard.getOrg() != null) {
                                        builder.field("displayname", vcard.getOrg().getOrgName() != null ? vcard.getOrg().getOrgName().trim() : "");
                                    }

                                    List<ExtendedType> extendedTypes = vcard.getExtendedTypes();
                                    if (extendedTypes != null) {
                                        for (ExtendedType et : extendedTypes) {
                                            if (et.getExtendedValue() != null && !et.getExtendedValue().trim().isEmpty()) {
                                                builder.field(et.getExtendedName(), et.getExtendedValue());
                                            }
                                        }
                                    }


                                    builder.field("vcard", v);
                                    builder.endObject();
                                }
                            } catch (VCardParseException e) {
                                log.warn(e.getMessage(), e);
                            } catch (NullPointerException e) {
                                log.warn("node: {} {}", id, e.getMessage(), e);
                            }
                        }

                    }
                }
                builder.endArray();
            }

            if (nodeData.getChildren() != null && !nodeData.getChildren().isEmpty()) {
                builder.startArray("children");
                for (NodeData child : nodeData.getChildren()) {
                    fillData(child, builder);
                }
                builder.endArray();

                Map<Date, List<Double>> ratingsAtDay = new HashMap<>();
                Map<Date, Double> ratingsAtDayAverage = new HashMap<>();
                double ratingAll;
                for (NodeData child : nodeData.getChildren()) {
                    if ("ccm:rating".equals(child.getNodeMetadata().getType())) {
                        Date modified = Date.from(Instant.parse((String) child.getNodeMetadata().getProperties().get(CCConstants.getValidGlobalName("cm:modified"))));
                        Calendar cal = Calendar.getInstance();
                        cal.setTime(modified);
                        cal.set(Calendar.HOUR_OF_DAY, 0);
                        cal.clear(Calendar.MINUTE);
                        cal.clear(Calendar.SECOND);
                        cal.clear(Calendar.MILLISECOND);
                        Date date = cal.getTime();
                        Double rating = Double.parseDouble((String) child.getNodeMetadata().getProperties().get(CCConstants.getValidGlobalName("ccm:rating_value")));
                        List<Double> dayRatings = ratingsAtDay.computeIfAbsent(date, k -> new ArrayList<>());
                        dayRatings.add(rating);
                    }
                }
                //average at day
                for (Map.Entry<Date, List<Double>> entry : ratingsAtDay.entrySet()) {
                    ratingsAtDayAverage.put(entry.getKey(), entry.getValue().stream().mapToDouble(x -> x).summaryStatistics().getAverage());
                }
                //average all
                ratingAll = ratingsAtDayAverage.values().stream().mapToDouble(x -> x).summaryStatistics().getAverage();

                for (Map.Entry<Date, Double> rating : ratingsAtDayAverage.entrySet()) {
                    builder.field("statistic_RATING_" + statisticDateFormatter.format(rating.getKey()), rating.getValue());
                }
                if ("ccm:io".equals(nodeData.getNodeMetadata().getType())) {
                    builder.field("statistic_RATING_null", ratingAll);
                }
            }
        }

        if (nodeData instanceof NodeDataProposal) {
            getProposalData((NodeDataProposal) nodeData, builder);
        }

        builder.endObject();
    }

    @Nullable
    public static Object getValue(Object value, String keyShortName) {
        if (value instanceof List<?> listvalue) {

            //i.e. cm:title
            if (!listvalue.isEmpty() && listvalue.get(0) instanceof Map) {
                value = getMultilangValue(listvalue);
            }

            //i.e. cclom:general_keyword
            if (!listvalue.isEmpty() && listvalue.get(0) instanceof List) {
                List<String> mvValue = new ArrayList<>();
                for (Object l : listvalue) {
                    String mlv = getMultilangValue((List<?>) l);
                    if (mlv != null) {
                        mvValue.add(mlv);
                    }
                }
                if (!mvValue.isEmpty()) {
                    value = (Serializable) mvValue;
                }//fix: mapper_parsing_exception Preview of field's value: '{locale=de_}']] (empty keyword)
                else {
                    log.info("fallback to \\”\\” for prop {} v:{}", keyShortName, value);
                    value = "";
                }
            }
        }
        return value;
    }

    public void mapWorkflowProtocol(Object value, @NonNull DataBuilder builder) {
        Collection<String> protocol;
        if (value instanceof Collection) {
            protocol = (Collection<String>) value;
        } else if (value instanceof String) {
            protocol = Collections.singletonList((String) value);
        } else {
            if (value != null) {
                log.warn("Unable to convert worfklow protocol of type {}", value.getClass().getName());
            } else {
                log.warn("Unable to convert worfklow protocol (null)");
            }
            return;
        }
        builder.startArray("workflow");
        protocol.stream().map(p -> {
                    try {
                        return new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create().fromJson(p, HashMap.class);
                    } catch (Throwable e) {
                        log.warn("Invalid json in workflow entry: {}", p, e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .forEach(builder::value);
        builder.endArray();

    }

    private void getProposalData(NodeDataProposal nodeData, @NonNull DataBuilder builder) throws IOException {
        if (nodeData.getCollection() != null) {
            builder.startArray("collections");
            fillData(nodeData.getCollection(), builder);
            builder.endArray();
        }
        if (nodeData.getOriginal() != null) {
            fillData(nodeData.getOriginal(), builder, "original");
        }
    }

    public void addNodePath(DataBuilder builder, NodeMetadata node) {
        {
            String[] pathEle = node.getPaths().get(0).getApath().split("/");
            builder.field("path", Arrays.copyOfRange(pathEle, 1, pathEle.length));
            builder.field("fullpath", StringUtils.join(Arrays.asList(Arrays.copyOfRange(pathEle, 1, pathEle.length)), '/'));
        }
        {
            List<String> fullPathAll = new ArrayList<>();
            for (Path path : node.getPaths()) {
                String[] pathEle = path.getApath().split("/");
                fullPathAll.add(StringUtils.join(Arrays.asList(Arrays.copyOfRange(pathEle, 1, pathEle.length)), '/'));
            }
            builder.field("fullpaths", fullPathAll);
        }
        {
           /*
            /{http://www.alfresco.org/model/application/1.0}company_home/{http://www.campuscontent.de/model/1.0}eeee/{http://www.campuscontent.de/model/1.0}Mercedes_x0020_Glk_x0020_-_x0020_1406.mp4/{http://www.alfresco.org/model/content/1.0}imgpreview
            */
            String shortPath = Stream.of(node.getPaths().get(0).getPath().split("/\\{"))
                    .skip(1)
                    // add previously removed "{"
                    .map(s -> "{" + s)
                    // get local name
                    .map(CCConstants::getValidLocalName)
                    .collect(Collectors.joining("/"));
            builder.field("fulldisplaypath", shortPath);
        }
    }

    public void refreshWorkspace() throws IOException {
        log.debug("starting");
        client.indices().refresh(req -> req.index(index));
        log.debug("returning");
    }

    public DataBuilder indexCollections(NodeMetadata usageOrProposal) throws IOException {

        UsageDetails result = getGetUsageDetails(usageOrProposal);
        if (result == null) return null;
        Query ioQuery = InternalQueries.queryByUUID("properties.sys:node-uuid", result.nodeIdIO);

        Hit<Map> searchHitCollection = getCollectionForUsage(result);
        if (searchHitCollection == null) return null;

        HitsMetadata<Map> ioSearchHits = this.search(ioQuery, 0, 1);
        if (ioSearchHits == null || (ioSearchHits.total() != null && ioSearchHits.total().value() == 0)) {
            log.warn("no io found for: {}", result.nodeIdIO);
            return null;
        }

        Hit<Map> hitIO = ioSearchHits.hits().get(0);
        if (hitIO == null || hitIO.source() == null) {
            return null;
        }

        if (searchHitCollection.source() == null) {
            return null;
        }

        Map<?, ?> propsIo = (Map<?, ?>) hitIO.source().get("properties");
        Map<?, ?> propsCollection = (Map<?, ?>) searchHitCollection.source().get("properties");

        log.info("adding collection data: " + propsCollection.get("cm:name") + " " + propsCollection.get("sys:node-dbid") + " IO: " + propsIo.get("cm:name") + " " + propsIo.get("sys:node-dbid"));

        List<Map<String, Object>> collections = (List<Map<String, Object>>) hitIO.source().get("collections");
        DataBuilder builder = new DataBuilder();
        builder.startObject();
        {
            builder.startArray("collections");
            if (collections != null && !collections.isEmpty()) {
                for (Map<String, Object> collection : collections) {
                    boolean colIsTheSame = searchHitCollection.source().get("dbid").equals(collection.get("dbid"));

                    Map<String, Object> relation = (Map<String, Object>) collection.get("relation");
                    if (relation != null && relation.get("dbid") != null) {
                        long dbidRelation = ((Number) (relation.get("dbid"))).longValue();
                        if (dbidRelation != usageOrProposal.getId()) {
                            colIsTheSame = false;
                        }
                    }
                    if (!colIsTheSame) {
                        builder.startObject();
                        for (Map.Entry<String, Object> entry : collection.entrySet()) {
                            if (entry.getKey().equals("children")) continue;
                            builder.field(entry.getKey(), entry.getValue());
                        }
                        builder.endObject();
                    }
                }
            }

            builder.startObject();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) searchHitCollection.source()).entrySet()) {
                if (entry.getKey().equals("children")) continue;
                builder.field(entry.getKey(), entry.getValue());
            }

            /*
             * check performance, if this call is to slow we could build the metadata structure by hand (instead using get)
             * for usages: we could resolve the collection_ref object to have alternate metadata and store it in relation field
             * but it would be another call and could make the indexing process slower.
             * for the future: maybe it's better to react on collection_ref object index actions than usage index actions
             * for perfromance reasons, we only fetch the relation for proposals, since they're not relevant for usages
             */
            addUsageRelation(usageOrProposal, builder);

            builder.endObject();
            builder.endArray();
        }
        builder.endObject();
        this.update(hitIO.id(), builder.build());
        this.refreshWorkspace();
        return builder;
    }

    private Hit<Map> getCollectionForUsage(UsageDetails result) throws IOException {
        Query collectionQuery = InternalQueries.queryByUUID("properties.sys:node-uuid", result.nodeIdCollection);
        HitsMetadata<Map> searchHitsCollection = this.search(collectionQuery, 0, 1);
        if (searchHitsCollection == null || searchHitsCollection.total().value() == 0) {
            log.warn("no collection found for: " + result.nodeIdCollection);
            return null;
        }
        return searchHitsCollection.hits().get(0);
    }

    private UsageDetails getGetUsageDetails(NodeMetadata usageOrProposal) throws IOException {
        String nodeIdCollection = null;
        String nodeIdIO = null;

        if (!(usageOrProposal.getType().equals("ccm:usage") || usageOrProposal.getType().equals("ccm:collection_proposal"))) {
            log.warn("wrong type:{}", usageOrProposal.getType());
            return null;
        }

        if (usageOrProposal.getType().equals("ccm:usage")) {
            String propertyUsageAppId = "{http://www.campuscontent.de/model/1.0}usageappid";
            String propertyUsageCourseId = "{http://www.campuscontent.de/model/1.0}usagecourseid";
            String propertyUsageParentNodeId = "{http://www.campuscontent.de/model/1.0}usageparentnodeid";

            nodeIdCollection = (String) usageOrProposal.getProperties().get(propertyUsageCourseId);
            nodeIdIO = (String) usageOrProposal.getProperties().get(propertyUsageParentNodeId);
            String usageAppId = (String) usageOrProposal.getProperties().get(propertyUsageAppId);

            //check if it is an collection usage
            if (!homeRepoId.equals(usageAppId)) {
                return null;
            }
        }
        if (usageOrProposal.getType().equals("ccm:collection_proposal")) {
            List<String> parentUuids = Arrays.asList(usageOrProposal.getPaths().get(0).getApath().split("/"));
            nodeIdCollection = parentUuids.stream().skip(parentUuids.size() - 1).findFirst().get();
            Object ioNodeRef = usageOrProposal.getProperties().get("{http://www.campuscontent.de/model/1.0}collection_proposal_target");
            if (ioNodeRef == null) {
                log.warn("no proposal target found for: {}", usageOrProposal.getNodeRef());
                return null;
            }
            nodeIdIO = Tools.getUUID(ioNodeRef.toString());
        }

        return new UsageDetails(nodeIdCollection, nodeIdIO);
    }

    /**
     * Add user activities to the elastic index. The timestamp will be stored as unix timestamp.
     *
     * @param activities list of activities to store
     * @throws IOException if elastic index is not accessible
     */
    public void addUserActivities(List<UserNodeActivity> activities) throws IOException {

        if (activities == null || activities.isEmpty()) {
            return;
        }

        //TODO when sharding: we have to put the userEvent into the same shard as the nodeId

        List<BulkOperation> operations = new ArrayList<>();
        for (UserNodeActivity activity : activities) {
            DataBuilder builder = new DataBuilder();
            {
                builder.startObject();
                {
                    builder.startObject("userEvent");
                    builder.field("nodeId", activity.getNodeId());
                    builder.field("initiator", activity.getUsername());
                    builder.field("type", activity.getType());
                    builder.field("timestamp", activity.getTimestamp().toInstant().toEpochMilli());
                    builder.endObject();
                }
                {
                    builder.startObject("join_children");
                    builder.field("name", "userEvent");
                    builder.field("parent", activity.getNodeId());
                    builder.endObject();
                }
                builder.endObject();
            }
            Object data = builder.build();

            operations.add(BulkOperation.of(op -> op.index(iop -> iop
                    .index(index)
                    .id("userEvents_" + activity.getId())
                    .routing(activity.getNodeId())
                    .document(data))));
        }

        log.info("starting bulk create activities");
        BulkResponse bulk = client.bulk(req -> req.index(index).operations(operations));
        if (bulk.errors()) {
            String errors = bulk.items()
                    .stream()
                    .map(BulkResponseItem::error)
                    .filter(Objects::nonNull)
                    .map(ErrorCause::reason)
                    .collect(Collectors.joining("\n"));
            log.error("bulk create activities failed with: {}", errors);
            throw new IOException("bulk create activities failed");
        }
        log.info("finished bulk create activities");
    }

    public void addShares(List<ShareInfo> shareInfos) throws IOException {
        if (shareInfos == null || shareInfos.isEmpty()) {
            return;
        }

        //TODO when sharding: we have to put the shares into the same shard as the nodeId

        List<BulkOperation> operations = new ArrayList<>();
        for (ShareInfo shareInfo : shareInfos) {
            DataBuilder builder = new DataBuilder();
            {
                builder.startObject();
                {
                    builder.startObject("share");
                    builder.field("id", shareInfo.getId());
                    builder.field("nodeId", shareInfo.getNodeId());
                    builder.field("sharedBy", shareInfo.getSharedBy());
                    builder.field("sharedWith", shareInfo.getSharedWith());
                    builder.field("shareStatus", shareInfo.getShareStatus());
                    builder.field("shareType", shareInfo.getShareType());
                    builder.field("timestamp", shareInfo.getTimestamp().toInstant().toEpochMilli());
                    builder.endObject();
                }
                {
                    builder.startObject("join_children");
                    builder.field("name", "share");
                    builder.field("parent", shareInfo.getNodeId());
                    builder.endObject();
                }
                builder.endObject();
            }
            Object data = builder.build();

            operations.add(BulkOperation.of(op -> op.index(iop -> iop
                    .index(index)
                    .id("shares_" + shareInfo.getId())
                    .routing(shareInfo.getNodeId())
                    .document(data))));
        }

        log.info("starting bulk create shares");
        BulkResponse bulk = client.bulk(req -> req.index(index).operations(operations));
        if (bulk.errors()) {
            String errors = bulk.items()
                    .stream()
                    .map(BulkResponseItem::error)
                    .filter(Objects::nonNull)
                    .map(ErrorCause::reason)
                    .collect(Collectors.joining("\n"));
            log.error("bulk create shares failed with: {}", errors);
            throw new IOException("bulk create shares failed");
        }
        log.info("finished bulk create shares");
    }

    public void deleteShares(Collection<Long> deletedShares) throws IOException {
        if (deletedShares == null || deletedShares.isEmpty()) {
            return;
        }

        List<BulkOperation> operations = deletedShares.stream().map(id -> BulkOperation.of(op -> op.delete(d -> d.index(index).id("shares_" + id.toString())))).collect(Collectors.toList());
        log.info("starting bulk delete shares");
        client.bulk(req -> req.index(index).operations(operations));
        log.info("finished bulk delete shares");
    }


    public void updateNodesWithSuggestions(String nodeId, @NotNull Collection<Map<String, Object>> suggestions) {
        // language=groovy
        final String SCRIPT_UPDATE_SUGGESTIONS = """
                ctx._source.suggestions = params.suggestions;
                """;

        UpdateByQueryResponse bulkByScrollResponse;
        try {
            bulkByScrollResponse = client.updateByQuery(req -> req
                    .index(index)
                    .query(q -> q.bool(b -> b
                            .should(s -> s.term(t -> t.field("_id").value(nodeId)))
                            .should(s -> s.bool(b2 -> b2
                                    .must(m -> m.term(t -> t.field("aspects").value(CCConstants.CCM_ASPECT_PUBLISHED)))
                                    .must(m -> m.term(t -> t.field("properties."+CCConstants.getValidLocalName(CCConstants.CCM_PROP_IO_PUBLISHED_ORIGINAL)).value(nodeId)))
                            ))))
                    .conflicts(Conflicts.Proceed)
                    .refresh(true)
                    .script(src -> src
                            .source(SCRIPT_UPDATE_SUGGESTIONS)
                            .params("suggestions", JsonData.of(suggestions)))
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log.debug("updated: {}", bulkByScrollResponse.updated());
        List<BulkIndexByScrollFailure> bulkFailures = bulkByScrollResponse.failures();
        for (BulkIndexByScrollFailure failure : bulkFailures) {
            log.error(failure.cause().toString(), failure.cause());
        }
    }

    private record UsageDetails(String nodeIdCollection, String nodeIdIO) {
    }

    /**
     * checks if its a collection usage by searching for collections.usagedbid, and removes replicated collection object
     */
    public void beforeDeleteCleanupCollectionReplicas(List<Node> nodes) throws IOException {
        log.info("starting: {}", nodes.size());

        if (nodes.isEmpty()) {
            log.info("returning 0");
            return;
        }

        List<BulkOperation> updateRequests = new ArrayList<>();
        for (Node node : nodes) {

            Query collectionCheckQuery = null;
            // try it is a usage or proposal
            Query queryUsage = InternalQueries.queryCollectionNodesViaUsage(node);
            HitsMetadata<Map> searchHitsIO = this.search(queryUsage, 0, 1);
            if (searchHitsIO.total().value() > 0) {
                collectionCheckQuery = queryUsage;
            }

            // try it is an collection
            Query queryCollection = InternalQueries.queryCollectionNodes(node.getId());
            if (collectionCheckQuery == null) {
                searchHitsIO = this.search(queryCollection, 0, 1);
                if (!searchHitsIO.hits().isEmpty()) {
                    collectionCheckQuery = queryCollection;
                }
            }


            //nothing to cleanup
            if (collectionCheckQuery == null) {
                continue;
            }
            boolean collectionDeleted = collectionCheckQuery.equals(queryCollection);
            log.info("cleanup collection cause {}", collectionDeleted ? "collection deleted" : "usage/proposal deleted");

            this.run(collectionCheckQuery, Map.class, hitIO -> {
                Map<?, ?> source = hitIO.source();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> collections = (List<Map<String, Object>>) source.get("collections");
                DataBuilder builder = new DataBuilder();
                builder.startObject();
                {
                    builder.startArray("collections");
                    if (collections != null && !collections.isEmpty()) {
                        for (Map<String, Object> collection : collections) {
                            long nodeDbId = node.getId();

                            Object collCeckAttValue;
                            if (collectionDeleted) {
                                collCeckAttValue = collection.get("dbid");
                            } else {
                                collCeckAttValue = ((Map<?, ?>) collection.get("relation")).get("dbid");
                            }

                            if (collCeckAttValue == null) {
                                log.info("replicated collection " + collection.get("dbid") + " does not have a property to check will leave it out");
                                continue;
                            }
                            long collectionAttValue = Long.parseLong(collCeckAttValue.toString());
                            if (nodeDbId != collectionAttValue) {
                                builder.startObject();
                                for (Map.Entry<String, Object> entry : collection.entrySet()) {
                                    builder.field(entry.getKey(), entry.getValue());
                                }
                                builder.endObject();
                            }
                        }
                    }
                    builder.endArray();
                }
                builder.endObject();

                updateRequests.add(BulkOperation.of(op -> op
                        .update(up -> up.index(index)
                                .id(hitIO.id())
                                .action(a -> a.doc(builder.build())))));
            });
        }

        Collection<List<BulkOperation>> partitions = Partition.getPartitions(updateRequests, bulkSizeElastic);
        for (List<BulkOperation> p : partitions) {
            this.updateBulk(p);
        }
        log.debug("returning");
    }

    public void beforeDeleteCleanupChildrenReplicas(List<Node> nodes) throws IOException {
        log.info("starting: {}", nodes.size());
        if (nodes.isEmpty()) {
            log.info("returning 0");
        }

        List<BulkOperation> updateRequests = new ArrayList<>();
        for (Node node : nodes) {
            Query query = InternalQueries.queryChildrenNodes(node.getId());
            this.run(query, Map.class, hitIO -> {
                Map<?, ?> source = hitIO.source();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) source.get("children");
                DataBuilder builder = new DataBuilder();
                builder.startObject();
                {
                    builder.startArray("children");
                    if (children != null && !children.isEmpty()) {
                        for (Map<String, Object> child : children) {
                            long childDbId = Long.parseLong(child.get("dbid").toString());
                            if (node.getId() != childDbId) {
                                builder.startObject();
                                for (Map.Entry<String, Object> entry : child.entrySet()) {
                                    builder.field(entry.getKey(), entry.getValue());
                                }
                                builder.endObject();
                            } else {
                                log.info("removing child {} form parent {}", childDbId, hitIO.id());
                            }
                        }
                    }
                    builder.endArray();
                }
                builder.endObject();
                String parentId = hitIO.id();
                updateRequests.add(BulkOperation.of(op -> op
                        .update(up -> up.index(index)
                                .id(parentId)
                                .action(a -> a.doc(builder.build())))));
            });
        }
        Collection<List<BulkOperation>> partitions = Partition.getPartitions(updateRequests, bulkSizeElastic);
        for (List<BulkOperation> p : partitions) {
            this.updateBulk(p);
        }
        log.info("finished:");
    }

    public void syncCollectionReplicas(NodeMetadataSimple collection) throws IOException {
        log.info("starting for collection: {}", collection.getNodeRef());
        this.run(InternalQueries.queryCollectionNodes(collection.getId()), maxCollectionChildItemsUpdateSize, Map.class, (hit) -> {
            try {
                onUpdateRefreshUsageCollectionReplicas(new NodeMetadataSimple(hit.source()), true, false);
            } catch (IOException e) {
                log.warn("error refreshing collections " + collection.getNodeRef(), e);
            }
        });
        log.info("finished for collection: " + collection.getNodeRef());
    }

    public void onUpdateRefreshUsageCollectionReplicas(NodeMetadataSimple node, boolean update, boolean resyncIndex) throws IOException {
        final String query, queryProposal;
        // collect already written collections
        Set<String> collections = new HashSet<>();
        if ("ccm:io".equals(node.getType())) {
            query = "properties.ccm:usageparentnodeid.keyword";
            queryProposal = "properties.ccm:collection_proposal_target.keyword";
        } else {
            log.info("can not handle collections for type:{}", node.getType());
            return;
        }
        AtomicBoolean hasCollections = new AtomicBoolean(false);
        log.info("updating collections for {} {}", node.getType(), node.getId());
        DataBuilder builder = new DataBuilder();
        builder.startObject();
        builder.startArray("collections");

        //find usages for collection
        Query queryUsages = InternalQueries.queryUsages(node, query);

        Query queryProposals = InternalQueries.queryProposals(node, queryProposal);
        long startTimeMs = System.currentTimeMillis();
        SearchHitsRunner.IOConsumer<Hit<Map>> action = hit -> {
            long dbId = ((Number) hit.source().get("dbid")).longValue();
            GetNodeMetadataParam param = new GetNodeMetadataParam();
            param.setNodeIds(Arrays.asList(dbId));
            List<NodeMetadata> nodeMetadataByIds = alfrescoClient.getNodeMetadataByIds(List.of(dbId));
            if (nodeMetadataByIds == null || nodeMetadataByIds.isEmpty()) {
                log.warn("could not find usage/proposal object in alfresco with dbid:" + dbId);
                return;
            }

            NodeMetadata usage = nodeMetadataByIds.get(0);
            log.debug("Is update: {}", update);

            log.info("running indexCollections for usage: {}", dbId);
            try {
                UsageDetails result = getGetUsageDetails(usage);
                if (result == null) {
                    return;
                }
                synchronized (collections) {
                    // we need to track all because for each relation there needs to be kept track regarding the usage for later deletion
                    //if(!collections.contains(result.nodeIdCollection)) {
                    collections.add(result.nodeIdCollection);
                    Hit<Map> collection = getCollectionForUsage(result);
                    if (collection != null) {
                        hasCollections.set(true);
                        builder.startObject();
                        for (Map.Entry<String, Object> entry : ((Map<String, Object>) collection.source()).entrySet()) {
                            if (entry.getKey().equals("children") || entry.getKey().equals("collections")) {
                                continue;
                            }
                            builder.field(entry.getKey(), entry.getValue());
                        }
                        addUsageRelation(usage, builder);
                        builder.endObject();
                    }
                    // }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        // run queries and apply action above

        this.run(queryUsages, 25, update ? maxCollectionChildItemsUpdateSize : null, null, Map.class, action);
        this.run(queryProposals, 25, update ? maxCollectionChildItemsUpdateSize : null, null, Map.class, action);
        builder.endArray();
        builder.endObject();
        // since the node was indexed before an explicit write is not required and slows down the performance
        String nodeId = Tools.getUUID(node.getNodeRef());
        if (!"ccm:io".equals(node.getType()) || hasCollections.get()) {
            this.update(nodeId, builder.build());
            if (resyncIndex) {
                this.refreshWorkspace();
            }
            if (node.getNodeRef() != null) {
                log.info("Index Collections done {} - no collections to index ({}ms)", nodeId, System.currentTimeMillis() - startTimeMs);
            }
        } else {
            log.info("Index Collections done {} - no collections to index ({}ms)", nodeId, System.currentTimeMillis() - startTimeMs);
        }


    }

    private void addUsageRelation(NodeMetadata usage, DataBuilder builder) throws IOException {
        if (usage.getType().equals("ccm:collection_proposal")) {
            fillData(alfrescoClient.getNodeData(Collections.singletonList(usage), FetchParameters.MINIMAL).get(0), builder, "relation");
        } else {
            fillData(NodeData.builder().nodeMetadata(usage).build(), builder, "relation");
        }
    }

    public static String getMultilangValue(List<?> values) {
        if (values.size() > 1) {
            // find german value i.e for Documents/Images edu folder
            String value = null;
            String defaultValue = null;

            for (Object item : values) {
                Map<?, ?> m = (Map<?, ?>) item;
                //default is {key="locale",value="de"},{key="value",value="Deutsch"}
                if (m.size() > 2) {
                    throw new RuntimeException("language map has only one value");
                }
                defaultValue = (String) m.get("value");
                if ("de".equals(m.get("locale")) || "de_".equals(m.get("locale"))) {
                    value = (String) m.get("value");
                }
            }
            if (value == null) value = defaultValue;
            return value;
        } else if (values.size() == 1) {
            Map<?, ?> multilangValue = (Map<?, ?>) values.get(0);
            return (String) multilangValue.get("value");
        } else {
            return null;
        }
    }

    public boolean exists(String id) throws IOException {
        return exists(id, index);
    }

    public boolean exists(String id, String index) throws IOException {
        return client.exists(req -> req
                        .index(index)
                        .id(id))
                .value();
    }

    public void delete(List<Node> nodes) throws IOException {
        delete(nodes, index);
    }

    public void delete(List<Node> nodes, String index) throws IOException {
        log.info("starting delete size:{}", nodes.size());
        for (Node node : nodes) {
            log.debug("nodeid to delete: {} / {}", node.getNodeRef(), node.getId());
        }
        if (!nodes.isEmpty()) {
            BulkResponse response = client.bulk(req -> req
                    .index(index)
                    .operations(
                            nodes.stream().map(n -> BulkOperation.of(
                                            b -> b.delete(d -> d.index(index).id(Tools.getUUID(n.getNodeRef())))
                                    )
                            ).collect(Collectors.toList())));
            if (response.items().size() != nodes.size()) {
                log.error("Errors occured while deleting nodes: Actual Deleted count {} does not match actual count: {}", response.items().size(), nodes.size());
            }
            for (BulkResponseItem item : response.items()) {
                if (item.error() != null) {
                    log.error("{} dbnodeid: {}", item.error().causedBy(), item.id());
                }
            }
        }
        log.debug("returning delete");
    }

    public HitsMetadata<Map> search(Query queryBuilder, int from, int size) throws IOException {
        return this.search(queryBuilder, from, size, null, Map.class);
    }

    public <T> HitsMetadata<T> search(Query query, int from, int size, List<String> excludes, Class<T> modelClass) throws IOException {
        SearchResponse<T> searchResponse = client.search(req -> {
                    req.index(index)
                            .query(query)
                            .from(from)
                            .size(size)
                            .trackTotalHits(t -> t.enabled(true));
                    if (excludes != null) {
                        req.source(src -> src.filter(fetch -> fetch.excludes(excludes)));
                    }
                    return req;
                }
                , modelClass);
        return searchResponse.hits();
    }

    public <T> void scroll(Query query, int pageSize, Integer maxResultsSize, String scrollTimeout, List<String> excludes, List<SortOptions> sortOptions, Class<T> modelClass, SearchHitsRunner.IOConsumer<Hit<T>> hitConsumer) throws IOException {

        String scrollId = null;
        try {
            Time time = Time.of(t -> t.time(scrollTimeout));
            SearchRequest.Builder req = new SearchRequest.Builder()
                    .index(index)
                    .scroll(time)
                    .size(pageSize)
                    .query(query).trackTotalHits(t -> t.enabled(true));
            if (excludes != null) {
                req.source(src -> src.filter(fetch -> fetch.excludes(excludes)));
            }
            if (sortOptions != null) {
                req.sort(sortOptions);
            }

            HitsMetadata<T> hits;
            ResponseBody<T> searchResponse;
            long hitsProcessed = 0;
            do {
                if (scrollId == null) {
                    searchResponse = client
                            .search(req.build(), modelClass);
                } else {
                    final String usedScrollId = scrollId;
                    searchResponse = client
                            .scroll(scroll -> scroll.scrollId(usedScrollId).scroll(time), modelClass);
                }
                scrollId = searchResponse.scrollId();
                hits = searchResponse.hits();

                for (Hit<T> hit : hits.hits()) {
                    if (hitConsumer != null) hitConsumer.accept(hit);
                    hitsProcessed++;
                    if (maxResultsSize != null && (hitsProcessed == maxResultsSize)) {
                        log.debug("stop scrolling cause {} reached. query:{}", maxResultsSize, query);
                        return;
                    }
                }
                log.debug("processed {} searchhits. query:{}", hitsProcessed, query);
            } while (!hits.hits().isEmpty());
        } finally {
            String fscrollId = scrollId;
            if (scrollId != null && !scrollId.isEmpty()) {
                client.clearScroll(cs -> cs.scrollId(fscrollId));
            }
        }
    }

    /**
     * Retrieves a mapping of node reference IDs to their associated property values for a specified property.
     *
     * @param nodeRefs a list of node reference strings representing the nodes whose property values are to be retrieved.
     * @param property the name of the property whose value should be extracted from the source data.
     * @return a map where the key is the node reference ID and the value is the Serializable value of the specified property
     * for that node. The map will not include entries for nodes where the specified property is null or absent.
     * @throws IOException if an error occurs during the retrieval process.
     */
    public Map<String, Serializable> getProperty(Collection<String> nodeRefs, String property) throws IOException {
        List<String> excludes = new ArrayList<>();
        excludes.add("preview");
        excludes.add("content");
        Map<String, Map<String, Object>> sourceMap = getSourceMap(nodeRefs, excludes);

        return sourceMap.entrySet()
                .stream()
                .map(x -> new AbstractMap.SimpleImmutableEntry<>(x.getKey(), (Serializable) x.getValue().get(property)))
                .filter(x -> Objects.nonNull(x.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, AbstractMap.SimpleImmutableEntry::getValue));
    }

    /**
     * Retrieves a mapping of node reference IDs to their associated source data.
     *
     * @param nodeRefs a list of node reference strings representing the nodes whose source data is to be retrieved.
     * @return a map where the key is the node reference ID and the value is a map representing the source data for that node.
     * The returned map may not contain all nodeRefs provided in the input.
     * @throws IOException if an error occurs during the retrieval process.
     */
    public Map<String, Map<String, Object>> getSourceMap(Collection<String> nodeRefs) throws IOException {
        return this.getSourceMap(nodeRefs, null);
    }

    /**
     * Retrieves a mapping of node reference IDs to their associated source data,
     * filtered based on the provided excludes list.
     *
     * @param nodeRefs a list of node reference strings representing the nodes whose source data is to be retrieved.
     * @param excludes a list of fields to exclude from the source data in the search results.
     * @return a map where the key is the node reference ID and the value is a map representing the source data for that node. The returned may not contain all nodeRefs provided by the input args.
     * @throws IOException if an error occurs during the search operation.
     */
    public Map<String, Map<String, Object>> getSourceMap(Collection<String> nodeRefs, List<String> excludes) throws IOException {

        List<String> uuids = nodeRefs.stream().map(Tools::getUUID).toList();
//        String protocol = Tools.getProtocol(nodeRef);
//        String identifier = Tools.getIdentifier(nodeRef);
//        Query query = InternalQueries.queryByUUID(uuid, protocol, identifier);

        HitsMetadata<Map> sh = this.search(Query.of(q -> q.ids(x -> x.values(uuids))), 0, nodeRefs.size(), excludes, Map.class);
        if (sh == null || sh.hits().isEmpty()) {
            return Collections.emptyMap();
        }

        //noinspection unchecked
        return sh.hits()
                .stream()
                .filter(x -> Objects.nonNull(x.source()))
                .collect(Collectors.toMap(Hit::id, Hit::source));
    }

    /**
     * @return true when all uuids already exist in index
     */
    public boolean updateNodeStatistics(Map<String, List<org.edu_sharing.generated.repository.backend.services.rest.client.model.NodeData>> nodeStatistics) throws IOException {

        AtomicBoolean allInIndex = new AtomicBoolean();
        allInIndex.set(true);

        try {
            Collection<List<Map.Entry<String, List<org.edu_sharing.generated.repository.backend.services.rest.client.model.NodeData>>>> partitions = Partition.getPartitions(nodeStatistics.entrySet(), bulkSizeElastic);
            int page = 0;
            for (List<Map.Entry<String, List<org.edu_sharing.generated.repository.backend.services.rest.client.model.NodeData>>> entries : partitions) {
                log.info("starting with page:{} collection size:{}", page, entries.size());
                try {
                    List<BulkOperation> bulk = new ArrayList<>();
                    for (Map.Entry<String, List<org.edu_sharing.generated.repository.backend.services.rest.client.model.NodeData>> entry : entries) {
                        String uuid = entry.getKey();
                        List<org.edu_sharing.generated.repository.backend.services.rest.client.model.NodeData> statistics = entry.getValue();
                        if (statistics == null || statistics.isEmpty()) continue;

                        if (!this.exists(uuid, index)) {
                            log.info("uuid:{} is not in elastic in elastic index", uuid);
                            allInIndex.set(false);
                            continue;
                        }

                        DataBuilder builder = new DataBuilder();
                        builder.startObject();
                        for (org.edu_sharing.generated.repository.backend.services.rest.client.model.NodeData nodeStatistic : statistics) {
                            if (nodeStatistic == null) {
                                log.debug("there is a null value in statistics list:{}", uuid);
                                continue;
                            }
                            if (nodeStatistic.getCounts() == null || nodeStatistic.getCounts().isEmpty()) continue;
                            String DOWNLOAD = "DOWNLOAD_MATERIAL";
                            String VIEW = "VIEW_MATERIAL";
                            String fieldNameDownload = "statistic_" + DOWNLOAD + "_" + nodeStatistic.getTimestamp();
                            String fieldNameView = "statistic_" + VIEW + "_" + nodeStatistic.getTimestamp();
                            Integer download = nodeStatistic.getCounts().get(DOWNLOAD);
                            Integer view = nodeStatistic.getCounts().get(VIEW);
                            if (download != null && download > 0) {
                                builder.field(fieldNameDownload, download);
                            }
                            if (view != null && view > 0) {
                                builder.field(fieldNameView, view);
                            }

                        }

                        builder.endObject();
                        bulk.add(BulkOperation.of(req -> req
                                .update(i -> i
                                        .index(index)
                                        .id(uuid)
                                        .action(a -> a.doc(builder.build())))));
                    }
                    this.updateBulk(bulk);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                page++;
            }
        } catch (RuntimeException e) {
            throw (IOException) e.getCause();
        }

        return allInIndex.get();
    }


    public void cleanUpNodeStatistics(List<String> nodeUuids) throws IOException {
        log.info("starting cleanUpNodeStatistics");
        List<String> excludes = new ArrayList<>();
        excludes.add("preview");
        excludes.add("content");
        Map<String, Map<String, Object>> sourcesMap = getSourceMap(nodeUuids.stream().map(x -> "workspace://SpacesStore/" + x).toList(), excludes);

        for (Map.Entry<String, Map<String, Object>> entry : sourcesMap.entrySet()) {
            String nodeUuid = entry.getKey();
            Map<String, Object> sourceMap = entry.getValue();

            String type = (String) sourceMap.get("type");
            if ("ccm:io".equals(type)) {
                List<String> propsToRemove = new ArrayList<>();

                Calendar cal = Calendar.getInstance();
                cal.add(Calendar.DAY_OF_YEAR, -statisticHistoryInDays);
                for (Map.Entry<String, Object> propEntry : sourceMap.entrySet()) {
                    if (!propEntry.getKey().startsWith("statistic_") || propEntry.getKey().startsWith("statistic_RATING")) {
                        continue;
                    }

                    String prefixPattern = "statistic_[a-zA-Z_]*";
                    String datePattern = "[0-9]{4}-[0-9]{2}-[0-9]{2}";
                    if (!propEntry.getKey().matches(prefixPattern + datePattern)) {
                        continue;
                    }

                    String[] split = Pattern.compile(prefixPattern).split(propEntry.getKey());
                    try {
                        Date date = statisticDateFormatter.parse(split[1]);
                        if (cal.getTime().getTime() > date.getTime()) {
                            propsToRemove.add(propEntry.getKey());
                        }
                    } catch (ParseException e) {
                        log.warn("can not get date in: {}", propEntry.getKey());
                    }
                }

                if (propsToRemove.isEmpty()) {
                    return;
                }

                log.info("remove for {}: {}", nodeUuid, String.join(",", propsToRemove));

                this.update(req -> req
                                .index(index)
                                .id(nodeUuid)
                                .script(scr -> scr
                                        .lang("painless")
                                        .source("for(String prop : params.propsToRemove){ctx._source.remove(prop)}")
                                        .params("propsToRemove", JsonData.of(propsToRemove))),
                        Map.class);
            }

            log.info("returning cleanUpNodeStatistics");
        }
    }
}
