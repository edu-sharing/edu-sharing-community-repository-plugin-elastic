package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.BulkIndexByScrollFailure;
import co.elastic.clients.elasticsearch._types.Conflicts;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.json.JsonData;
import co.elastic.clients.util.ObjectBuilder;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NonNull;
import net.sourceforge.cardme.engine.VCardEngine;
import net.sourceforge.cardme.vcard.VCard;
import net.sourceforge.cardme.vcard.exceptions.VCardParseException;
import net.sourceforge.cardme.vcard.types.ExtendedType;
import net.sourceforge.cardme.vcard.types.NType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tomcat.util.buf.StringUtils;
import org.edu_sharing.elasticsearch.alfresco.client.*;
import org.edu_sharing.elasticsearch.edu_sharing.client.EduSharingClient;
import org.edu_sharing.elasticsearch.edu_sharing.client.NodeStatistic;
import org.edu_sharing.elasticsearch.elasticsearch.utils.DataBuilder;
import org.edu_sharing.elasticsearch.tools.ScriptExecutor;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.Partition;
import org.edu_sharing.repository.client.tools.CCConstants;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class WorkspaceService {

    public static final String CONTRIBUTOR_REGEX = "ccm:[a-zA-Z]*contributer_[a-zA-Z_-]*";

    @Value("${statistic.historyInDays}")
    int statisticHistoryInDays;

    @Value("${maxContentLength}")
    int maxContentLength;

    @Value("${elastic.maxCollectionChildItemsUpdateSize}")
    int maxCollectionChildItemsUpdateSize;

    @Value("${tracker.bulk.size.elastic}")
    int bulkSizeElastic;

    private final Logger logger = LogManager.getLogger(WorkspaceService.class);
    private final SimpleDateFormat statisticDateFormatter = new SimpleDateFormat("yyyy-MM-dd");
    private final String homeRepoId;
    private final ElasticsearchClient client;
    private final ScriptExecutor scriptExecutor;
    private final AtomicInteger nodeCounter = new AtomicInteger(0);
    private final AtomicLong lastNodeCount = new AtomicLong(System.currentTimeMillis());
    private final AlfrescoWebscriptClient alfrescoClient;
    private final SearchHitsRunner searchHitsRunner = new SearchHitsRunner(this);
    private final String index;

    public WorkspaceService(co.elastic.clients.elasticsearch.ElasticsearchClient client, ScriptExecutor scriptExecutor, EduSharingClient eduSharingClient, AlfrescoWebscriptClient alfrescoClient, IndexConfiguration workspace) {
        this.client = client;
        this.scriptExecutor = scriptExecutor;
        this.alfrescoClient = alfrescoClient;
        this.index = workspace.getIndex();
        this.homeRepoId = eduSharingClient.getHomeRepository().getId();
    }

    public void updateNodesWithAcl(final long aclId, final Map<String, List<String>> permissions) throws IOException {
        logger.debug("starting: {} ", aclId);

        UpdateByQueryResponse bulkByScrollResponse = client.updateByQuery(req -> req
                .index(index)
                .query(q -> q.term(t -> t.field("aclId").value(aclId)))
                .conflicts(Conflicts.Proceed)
                .refresh(true)
                .script(scr -> scr
                        .source("ctx._source.permissions=params")
                        .params(permissions.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, x -> JsonData.of(x.getValue())))))
        );

        logger.debug("updated: {}", bulkByScrollResponse.updated());
        List<BulkIndexByScrollFailure> bulkFailures = bulkByScrollResponse.failures();
        for (BulkIndexByScrollFailure failure : bulkFailures) {
            logger.error(failure.cause().toString(), failure.cause());
        }
    }

    public void update(long dbId, Object data) throws IOException {
        this.update(req -> req
                .index(index)
                .id(Long.toString(dbId))
                .doc(data), Void.class);
    }

    private <TDocument, TPartialDocument> void update(Function<
            UpdateRequest.Builder<TDocument, TPartialDocument>,
            ObjectBuilder<UpdateRequest<TDocument, TPartialDocument>>
            > request, Class<TDocument> tDocClass) throws IOException {

        UpdateResponse<TDocument> updateResponse = client.update(request, tDocClass);

        if (Objects.requireNonNull(updateResponse.result()) == Result.Created) {
            logger.info("object did not exist");
        }
    }

    public void updateBulk(List<BulkOperation> updateRequests) throws IOException {
        if (updateRequests.isEmpty()) {
            return;
        }

        BulkResponse response = client.bulk(req -> req.index(index).operations(updateRequests));
        for (BulkResponseItem item : response.items()) {
            if (item.error() != null) {
                logger.error(item.error().reason());
            }
        }
    }


    public void index(List<NodeData> nodes) throws IOException {
        logger.info("starting bulk index for {}", nodes.size());

        // TODO Missing required property 'BulkRequest.operations'
        boolean useBulkUpdate = true;

        List<BulkOperation> operations = new ArrayList<>();
        for (NodeData nodeData : nodes) {
            NodeMetadata node = nodeData.getNodeMetadata();
            DataBuilder builder = new DataBuilder();
            fillData(nodeData, builder);
            Object data = builder.build();
            operations.add(BulkOperation.of(op -> op.index(iop -> iop
                    .index(index)
                    .id(Long.toString(node.getId()))
                    .document(data))));

            if (nodeCounter.addAndGet(1) % 100 == 0) {
                logger.info("Processed " + nodeCounter.get() + " nodes (" + (System.currentTimeMillis() - lastNodeCount.get()) + "ms per last 100 nodes)");
                lastNodeCount.set(System.currentTimeMillis());
            }
        }

        if (useBulkUpdate && !operations.isEmpty()) {
            logger.info("starting bulk update:");
            BulkResponse bulkResponse = client.bulk(req -> req.index(index).operations(operations));
            logger.info("finished bulk update:");

            Map<Long, NodeData> collectionNodes = new HashMap<>();
            for (NodeData nodeData : nodes) {
                NodeMetadata node = nodeData.getNodeMetadata();
                if ((node.getType().equals("ccm:map") && node.getAspects().contains("ccm:collection"))
                        || (node.getType().equals("ccm:io") && !node.getAspects().contains("ccm:collection_io_reference"))) {
                    collectionNodes.put(node.getId(), nodeData);
                }
            }
            logger.info("start refresh index");
            this.refreshWorkspace();
            try {
                logger.info("start RefreshCollectionReplicas (" + bulkResponse.items().size() + ")");
                for (BulkResponseItem item : bulkResponse.items()) {
                    if (item.error() != null) {
                        logger.error("Failed indexing of " + item.id());
                        logger.error("Failed indexing of " + item.error().causedBy());
                        continue;
                    }

                    Long dbId = item.id() != null ? Long.parseLong(item.id()) : null;
                    NodeData nodeData = collectionNodes.get(dbId);
                    if (nodeData != null) {
                        onUpdateRefreshUsageCollectionReplicas(nodeData.getNodeMetadata(), item.operationType() == OperationType.Update || item.operationType() == OperationType.Index);
                    }
                }
                logger.info("finished RefreshCollectionReplicas");
            } catch (Throwable e) {
                logger.error(e.getMessage(), e);
                throw e;
            }

        }
        logger.info("returning");
    }

    public void fillData(NodeData nodeData, @NonNull DataBuilder builder) throws IOException {
        fillData(nodeData, builder, null);
    }

    void fillData(NodeData nodeData, @NonNull final DataBuilder builder, String objectName) throws IOException {
        NodeMetadata node = nodeData.getNodeMetadata();
        String storeRefProtocol = Tools.getProtocol(node.getNodeRef());
        String storeRefIdentifier = Tools.getIdentifier(node.getNodeRef());


        if (objectName != null) {
            builder.startObject(objectName);
        } else {
            builder.startObject();
        }
        {
            builder.field("aclId", node.getAclId());
            builder.field("txnId", node.getTxnId());
            builder.field("dbid", node.getId());

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
            if(!Objects.equals(objectName, "relation")) {
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

            if (node.getPaths() != null && !node.getPaths().isEmpty()) {
                addNodePath(builder, node);
            }

            if(nodeData.getReader() != null) {
                builder.startObject("permissions");
                builder.field("read", nodeData.getReader().getReaders());
                for (Map.Entry<String, List<String>> entry : nodeData.getPermissions().entrySet()) {
                    builder.field(entry.getKey(), entry.getValue());
                }
                builder.endObject();
            }
            //content
            /**
             *     "{http://www.alfresco.org/model/content/1.0}content": {
             *    "contentId": "279",
             *    "encoding": "UTF-8",
             *    "locale": "de_DE_",
             *    "mimetype": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
             *    "size": "8385"
             * },
             */
            @SuppressWarnings("unchecked")
            Map<String, Object> content = (Map<String, Object>) node.getProperties().get("{http://www.alfresco.org/model/content/1.0}content");
            if (content != null) {

                builder.startObject("content");
                builder.field("contentId", content.get("contentId"));
                builder.field("encoding", content.get("encoding"));
                builder.field("locale", content.get("locale"));
                builder.field("mimetype", content.get("mimetype"));
                builder.field("size", content.get("size"));
                if (nodeData.getFullText() != null) {
                    if (maxContentLength > 0 && nodeData.getFullText().length() > maxContentLength) {
                        logger.info("Node " + node.getNodeRef() + " has too large fulltext: " + nodeData.getFullText().length() + ". Will be truncated to " + maxContentLength);
                        builder.field("fulltext", nodeData.getFullText().substring(0, maxContentLength));
                    } else {
                        builder.field("fulltext", nodeData.getFullText());
                    }
                }
                builder.endObject();
            }


            Map<String, Serializable> contributorProperties = new HashMap<>();
            builder.startObject("properties");
            for (Map.Entry<String, Serializable> prop : node.getProperties().entrySet()) {

                String key = CCConstants.getValidLocalName(prop.getKey());
                if (key == null) {
                    logger.warn("unknown namespace: " + prop.getKey());
                    continue;
                }

                Serializable value = prop.getValue();
                if (key.matches(CONTRIBUTOR_REGEX)) {
                    if (value != null) {
                        contributorProperties.put(key, value);
                    }
                }

                if (prop.getValue() instanceof List) {
                    List listvalue = (List) prop.getValue();

                    //i.e. cm:title
                    if (!listvalue.isEmpty() && listvalue.get(0) instanceof Map) {
                        value = getMultilangValue(listvalue);
                    }

                    //i.e. cclom:general_keyword
                    if (!listvalue.isEmpty() && listvalue.get(0) instanceof List) {
                        List<String> mvValue = new ArrayList<>();
                        for (Object l : listvalue) {
                            String mlv = getMultilangValue((List) l);
                            if (mlv != null) {
                                mvValue.add(mlv);
                            }
                        }
                        if (!mvValue.isEmpty()) {
                            value = (Serializable) mvValue;
                        }//fix: mapper_parsing_exception Preview of field's value: '{locale=de_}']] (empty keyword)
                        else {
                            logger.info("fallback to \\”\\” for prop " + key + " v:" + value);
                            value = "";
                        }
                    }
                }
                if ("cm:modified".equals(key) || "cm:created".equals(key)) {

                    if (prop.getValue() != null) {
                        value = Date.from(Instant.parse((String) prop.getValue())).getTime();
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
                                logger.warn(e.getMessage());
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
                if ("ccm:wf_protocol".equals(key)) {
                    mapWorkflowProtocol(value, builder);
                }

                if (value != null) {

                    try {
                        builder.field(key, value);
                    } catch (Exception e) {
                        logger.warn("error parsing value field:" + key + "v" + value, e);
                    }
                }
            }
            builder.endObject();

            builder.field("aspects", node.getAspects());

            if (!contributorProperties.isEmpty()) {
                VCardEngine vcardEngine = new VCardEngine();
                builder.startArray("contributor");
                for (Map.Entry<String, Serializable> entry : contributorProperties.entrySet()) {
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
                                logger.warn(e.getMessage(), e);
                            } catch (NullPointerException e) {
                                logger.warn("node: " + id + " " + e.getMessage(), e);
                            }
                        }

                    }
                }
                builder.endArray();
            }
            if (nodeData.getNodePreview() != null) {
                builder.startObject("preview").
                        field("mimetype", nodeData.getNodePreview().getMimetype()).
                        field("type", nodeData.getNodePreview().getType()).
                        field("icon", nodeData.getNodePreview().isIcon()).
                        field("small", nodeData.getNodePreview().getSmall()).
                        //field("large", nodeData.getNodePreview().getLarge()).
                                endObject();
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

    void mapWorkflowProtocol(Serializable value, @NonNull DataBuilder builder) {
        Collection<String> protocol;
        if (value instanceof Collection) {
            protocol = (Collection<String>) value;
        } else if (value instanceof String) {
            protocol = Collections.singletonList((String) value);
        } else {
            logger.warn("Unable to convert worfklow protocol of type " + value.getClass().getName());
            return;
        }
        builder.startArray("workflow");
        protocol.stream().map(p -> {
                    try {
                        return new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create().fromJson(p, HashMap.class);
                    } catch (Throwable e) {
                        logger.warn("Invalid json in workflow entry: " + p, e);
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

    private void addNodePath(DataBuilder builder, NodeMetadata node) {
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
            String shortPath = List.of(node.getPaths().get(0).getPath().split("/\\{")).stream()
                    .skip(1)
                    // add previously removed "{"
                    .map(s -> "{"+s)
                    // get local name
                    .map(m ->CCConstants.getValidLocalName(m))
                    .collect(Collectors.joining("/"));
            builder.field("fulldisplaypath",shortPath);
        }
    }

    public void refreshWorkspace() throws IOException {
        logger.debug("starting");
        client.indices().refresh(req -> req.index(index));
        logger.debug("returning");
    }

    public DataBuilder indexCollections(NodeMetadata usageOrProposal) throws IOException {

        UsageDetails result = getGetUsageDetails(usageOrProposal);
        if (result == null) return null;
        Query ioQuery = InternalQueries.queryByUUID("properties.sys:node-uuid", result.nodeIdIO);

        Hit<Map> searchHitCollection = getCollectionForUsage(result);
        if (searchHitCollection == null) return null;

        HitsMetadata<Map> ioSearchHits = this.search(ioQuery, 0, 1);
        if (ioSearchHits == null || ioSearchHits.total().value() == 0) {
            logger.warn("no io found for: " + result.nodeIdIO);
            return null;
        }

        Hit<Map> hitIO = ioSearchHits.hits().get(0);

        Map propsIo = (Map) hitIO.source().get("properties");
        Map propsCollection = (Map) searchHitCollection.source().get("properties");


        logger.info("adding collection data: " + propsCollection.get("cm:name") + " " + propsCollection.get("sys:node-dbid") + " IO: " + propsIo.get("cm:name") + " " + propsIo.get("sys:node-dbid"));

        List<Map<String, Object>> collections = (List<Map<String, Object>>) hitIO.source().get("collections");
        DataBuilder builder = new DataBuilder();
        builder.startObject();
        {
            builder.startArray("collections");
            if (collections != null && collections.size() > 0) {
                for (Map<String, Object> collection : collections) {
                    boolean colIsTheSame = searchHitCollection.source().get("dbid").equals(collection.get("dbid"));

                    Map<String, Object> relation = (Map<String, Object>) collection.get("relation");
                    if (relation != null && relation.get("dbid") != null) {
                        long dbidRelation = ((Number) (relation.get("dbid"))).longValue();
                        if (dbidRelation != usageOrProposal.getId()) {
                            colIsTheSame = false;
                        }
                    }
                    if(!colIsTheSame) {
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

            /**
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
        int dbid = Integer.parseInt(hitIO.id());
        this.update(dbid, builder.build());
        this.refreshWorkspace();
        return builder;
    }

    private Hit<Map> getCollectionForUsage(UsageDetails result) throws IOException {
        Query collectionQuery = InternalQueries.queryByUUID("properties.sys:node-uuid", result.nodeIdCollection);
        HitsMetadata<Map> searchHitsCollection = this.search(collectionQuery, 0, 1);
        if (searchHitsCollection == null || searchHitsCollection.total().value() == 0) {
            logger.warn("no collection found for: " + result.nodeIdCollection);
            return null;
        }
        Hit<Map> searchHitCollection = searchHitsCollection.hits().get(0);
        return searchHitCollection;
    }

    private UsageDetails getGetUsageDetails(NodeMetadata usageOrProposal) throws IOException {
        String nodeIdCollection = null;
        String nodeIdIO = null;

        if (!(usageOrProposal.getType().equals("ccm:usage") || usageOrProposal.getType().equals("ccm:collection_proposal"))) {
            logger.warn("wrong type:" + usageOrProposal.getType());
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
            Serializable ioNodeRef = usageOrProposal.getProperties().get("{http://www.campuscontent.de/model/1.0}collection_proposal_target");
            if (ioNodeRef == null) {
                logger.warn("no proposal target found for: " + usageOrProposal.getNodeRef());
                return null;
            }
            nodeIdIO = Tools.getUUID(ioNodeRef.toString());
        }

        return new UsageDetails(nodeIdCollection, nodeIdIO);
    }

    @Data
    @AllArgsConstructor
    private static class UsageDetails {
        public final String nodeIdCollection;
        public final String nodeIdIO;
    }

    /**
     * checks if its a collection usage by searching for collections.usagedbid, and removes replicated collection object
     */
    public void beforeDeleteCleanupCollectionReplicas(List<Node> nodes) throws IOException {
        logger.info("starting: " + nodes.size());

        if (nodes.isEmpty()) {
            logger.info("returning 0");
            return;
        }

        List<BulkOperation> updateRequests = new ArrayList<>();
        for (Node node : nodes) {

            Query collectionCheckQuery = null;
            /**
             * try it is a usage or proposal
             */
            Query queryUsage = InternalQueries.queryCollectionNodesViaUsage(node);
            HitsMetadata<Map> searchHitsIO = this.search(queryUsage, 0, 1);
            if (searchHitsIO.total().value() > 0) {
                collectionCheckQuery = queryUsage;
            }

            /**
             * try it is an collection
             */
            Query queryCollection = InternalQueries.queryCollectionNodes(node);
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
            logger.info("cleanup collection cause " + (collectionDeleted ? "collection deleted" : "usage/proposal deleted"));

            searchHitsRunner.run(collectionCheckQuery, hitIO -> {
                Map source = hitIO.source();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> collections = (List<Map<String, Object>>) source.get("collections");
                DataBuilder builder = new DataBuilder();
                builder.startObject();
                {
                    builder.startArray("collections");
                    if (collections != null && collections.size() > 0) {
                        for (Map<String, Object> collection : collections) {
                            long nodeDbId = node.getId();

                            Object collCeckAttValue = null;
                            if (collectionDeleted) {
                                collCeckAttValue = collection.get("dbid");
                            } else {
                                collCeckAttValue = ((HashMap) collection.get("relation")).get("dbid");
                            }

                            if (collCeckAttValue == null) {
                                logger.info("replicated collection " + collection.get("dbid") + " does not have a property to check will leave it out");
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
                int dbid = Integer.parseInt(hitIO.id());

                updateRequests.add(BulkOperation.of(op -> op
                        .update(up -> up.index(index)
                                .id(Long.toString(dbid))
                                .action(a -> a.doc(builder.build())))));
            });
        }

        Collection<List<BulkOperation>> partitions = Partition.getPartitions(updateRequests, bulkSizeElastic);
        for(List<BulkOperation> p : partitions){
            this.updateBulk(p);
        }
        logger.info("returning");
    }

    private void onUpdateRefreshUsageCollectionReplicas(NodeMetadata node, boolean update) throws IOException {

        final String query;
        final String queryProposal;
        // collect already written collections
        Set<String> collections = new HashSet<>();
        if ("ccm:map".equals(node.getType())) {
            query = "properties.ccm:usagecourseid.keyword";
            queryProposal = "parentRef.id";
        } else if ("ccm:io".equals(node.getType())) {
            query = "properties.ccm:usageparentnodeid.keyword";
            queryProposal = "properties.ccm:collection_proposal_target.keyword";
        } else {
            logger.info("can not handle collections for type:" + node.getType());
            return;
        }

        logger.info("updating collections for " + node.getType() + " " + node.getId());
        DataBuilder builder = new DataBuilder();
        builder.startObject();
        builder.startArray("collections");

        //find usages for collection
        Query queryUsages = InternalQueries.queryUsages(node, query);

        Query queryProposals = InternalQueries.queryProposals(node, queryProposal);
        long startTimeMs = System.currentTimeMillis();
        Consumer<Hit<Map>> action = hit -> {
            long dbId = ((Number) hit.source().get("dbid")).longValue();
            GetNodeMetadataParam param = new GetNodeMetadataParam();
            param.setNodeIds(Arrays.asList(new Long[]{dbId}));
            List<NodeMetadata> nodeMetadataByIds = alfrescoClient.getNodeMetadataByIds(List.of(dbId));
            if (nodeMetadataByIds == null || nodeMetadataByIds.isEmpty()) {
                logger.warn("could not find usage/proposal object in alfresco with dbid:" + dbId);
                return;
            }

            NodeMetadata usage = nodeMetadataByIds.get(0);
            logger.debug("Is update: {}", update);

            logger.info("running indexCollections for usage: " + dbId);
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
                        if(collection != null) {
                            builder.startObject();
                            for (Map.Entry<String, Object> entry : ((Map<String, Object>) collection.source()).entrySet()) {
                                if (entry.getKey().equals("children")) continue;
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
        searchHitsRunner.run(queryUsages, 5, update ? maxCollectionChildItemsUpdateSize : null, action);
        searchHitsRunner.run(queryProposals, 5, update ? maxCollectionChildItemsUpdateSize : null, action);
        builder.endArray();
        builder.endObject();
        // apply changes
        this.update(node.getId(), builder.build());
        this.refreshWorkspace();
        if(node.getNodeRef() != null) {
            logger.info("Index Collections done " + Tools.getUUID(node.getNodeRef()) + " (" + ((System.currentTimeMillis() - startTimeMs)) + "ms)");
        }
    }

    private void addUsageRelation(NodeMetadata usage, DataBuilder builder) throws IOException {
        if(usage.getType().equals("ccm:collection_proposal")) {
            fillData(alfrescoClient.getNodeData(Collections.singletonList(usage), FetchParameters.MINIMAL).get(0), builder, "relation");
        } else {
            fillData(NodeData.builder().nodeMetadata(usage).build(), builder, "relation");
        }
    }

    private String getMultilangValue(List<?> values) {
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
        return client.exists(req -> req
                        .index(index)
                        .id(id))
                .value();
    }

    public void delete(List<Node> nodes) throws IOException {
        logger.info("starting delete size:" + nodes.size());
        for (Node node : nodes) {
            logger.debug("nodeid to delete: " + node.getNodeRef() + " / " + node.getId());
        }
        if (!nodes.isEmpty()) {
            BulkResponse response = client.bulk(req -> req
                    .index(index)
                    .operations(
                            nodes.stream().map(n -> BulkOperation.of(
                                            b -> b.delete(d -> d.index(index).id(Long.toString(n.getId())))
                                    )
                            ).collect(Collectors.toList())));
            if(response.items().size() != nodes.size()) {
                logger.error("Errors occured while deleting nodes: Actual Deleted count " + response.items().size() + " does not match actual count: " + nodes.size());
            }
            for (BulkResponseItem item : response.items()) {
                if (item.error() != null) {
                    logger.error(item.error().causedBy() + " dbnodeid: " + item.id());
                }
            }
        }
        logger.debug("returning delete");
    }

    public HitsMetadata<Map> search(Query queryBuilder, int from, int size) throws IOException {
        return this.search(queryBuilder, from, size, null);
    }

    public HitsMetadata<Map> search(Query query, int from, int size, List<String> excludes) throws IOException {
        SearchResponse<Map> searchResponse = client.search(req -> {
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
                , Map.class);
        return searchResponse.hits();
    }

    public Serializable getProperty(String nodeRef, String property) throws IOException {
        List<String> excludes = new ArrayList<>();
        excludes.add("preview");
        excludes.add("content");
        Map<String, Object> sourceMap = getSourceMap(nodeRef, excludes);
        return (sourceMap == null) ? null : (Serializable) sourceMap.get(property);
    }

    public Map<String, Object> getSourceMap(String nodeRef) throws IOException {
        return this.getSourceMap(nodeRef, null);
    }

    public Map<String, Object> getSourceMap(String nodeRef, List<String> excludes) throws IOException {

        String uuid = Tools.getUUID(nodeRef);
        String protocol = Tools.getProtocol(nodeRef);
        String identifier = Tools.getIdentifier(nodeRef);
        Query query = InternalQueries.queryByUUID(uuid, protocol, identifier);

        HitsMetadata<Map> sh = this.search(query, 0, 1, excludes);
        if (sh == null || sh.total().value() == 0) {
            return null;
        }

        Hit<Map> searchHit = sh.hits().get(0);
        //noinspection unchecked
        return (Map<String, Object>) searchHit.source();
    }

    /**
     * @return true when all uuids already exist in index
     */
    public boolean updateNodeStatistics(Map<String, List<NodeStatistic>> nodeStatistics) throws IOException {

        AtomicBoolean allInIndex = new AtomicBoolean();
        allInIndex.set(true);

        try {
            Collection<List<Map.Entry<String, List<NodeStatistic>>>> partitions = Partition.getPartitions(nodeStatistics.entrySet(), bulkSizeElastic);
            int page = 0;
            for (List<Map.Entry<String, List<NodeStatistic>>> entries : partitions) {
                logger.info("starting with page:" + page + " collection size:" + entries.size());
                try {
                    List<BulkOperation> bulk = new ArrayList<>();
                    for (Map.Entry<String, List<NodeStatistic>> entry : entries) {
                        String uuid = entry.getKey();
                        List<NodeStatistic> statistics = entry.getValue();
                        if (statistics == null || statistics.isEmpty()) continue;

                        String nodeRef = CCConstants.STORE_WORKSPACES_SPACES + "/" + uuid;
                        Serializable value = this.getProperty(nodeRef, "dbid");
                        if (value == null) {
                            String nodeRefArchive = CCConstants.ARCHIVE_STOREREF + "/" + uuid;
                            value = this.getProperty(nodeRefArchive, "dbid");

                            if (value == null) {
                                logger.info("uuid:" + uuid + " is not in elastic in elastic index");
                                allInIndex.set(false);
                                continue;
                            }
                        }

                        long dbid = ((Number) value).longValue();

                        DataBuilder builder = new DataBuilder();
                        builder.startObject();
                        for (NodeStatistic nodeStatistic : statistics) {
                            if (nodeStatistic == null) {
                                logger.debug("there is a null value in statistics list:" + nodeRef);
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
                                        .id(Long.toString(dbid))
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
        logger.info("starting cleanUpNodeStatistics");
        for (String uuid : nodeUuids) {
            cleanUpNodeStatistics(uuid);
        }
        logger.info("returning cleanUpNodeStatistics");
    }


    public void cleanUpNodeStatistics(String nodeUuid) throws IOException {

        List<String> excludes = new ArrayList<>();
        excludes.add("preview");
        excludes.add("content");
        Map<String, Object> sourceMap = getSourceMap("workspace://SpacesStore/" + nodeUuid, excludes);
        if (sourceMap == null) {
            return;
        }

        long dbid = ((Number) sourceMap.get("dbid")).longValue();
        String id = Long.toString(dbid);
        String type = (String) sourceMap.get("type");

        if ("ccm:io".equals(type)) {
            List<String> propsToRemove = new ArrayList<>();

            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -statisticHistoryInDays);
            for (Map.Entry<String, Object> entry : sourceMap.entrySet()) {
                if (!entry.getKey().startsWith("statistic_") || entry.getKey().startsWith("statistic_RATING")) {
                    continue;
                }

                String prefixPattern = "statistic_[a-zA-Z_]*";
                String datePattern = "[0-9]{4}-[0-9]{2}-[0-9]{2}";
                if (!entry.getKey().matches(prefixPattern + datePattern)) {
                    continue;
                }

                String[] split = Pattern.compile(prefixPattern).split(entry.getKey());
                try {
                    Date date = statisticDateFormatter.parse(split[1]);
                    if (cal.getTime().getTime() > date.getTime()) {
                        propsToRemove.add(entry.getKey());
                    }
                } catch (ParseException e) {
                    logger.warn("can not get date in: " + entry.getKey());
                }
            }

            if (propsToRemove.isEmpty()) {
                return;
            }

            logger.info("remove for " + id + ": " + String.join(",", propsToRemove));

            this.update(req -> req
                            .index(index)
                            .id(id)
                            .script(scr -> scr
                                    .lang("painless")
                                    .source("for(String prop : params.propsToRemove){ctx._source.remove(prop)}")
                                    .params("propsToRemove", JsonData.of(propsToRemove))),
                    Map.class);
        }
    }
}
