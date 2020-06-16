package org.edu_sharing.elasticsearch.elasticsearch.client;


import org.apache.http.HttpHost;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeData;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.alfresco.client.Reader;
import org.edu_sharing.elasticsearch.tools.Constants;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.get.GetIndexRequest;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.replication.ReplicationResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;

import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.io.Serializable;
import java.time.Instant;
import java.util.*;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

@Component
public class ElasticsearchClient {

    @Value("${elastic.host}")
    String elasticHost;

    @Value("${elastic.port}")
    int elasticPort;

    @Value("${elastic.protocol}")
    String elasticProtocol;

    Logger logger = LogManager.getLogger(ElasticsearchClient.class);

    public static String INDEX_WORKSPACE = "workspace";

    public static String INDEX_TRANSACTIONS = "transactions";

    public static String TYPE = "node";

    final static String ID_TRANSACTION = "1";

    final static String ID_ACL_CHANGESET = "2";

   // final static String TYPE = "doc";

    @PostConstruct
    public void init() throws IOException {
        createIndexIfNotExists(INDEX_TRANSACTIONS);
        createIndexIfNotExists(INDEX_WORKSPACE);
    }

    private void createIndexIfNotExists(String index) throws IOException{
        RestHighLevelClient client = getClient();

        if(!indexExists(index)){
            CreateIndexRequest createIndexRequest = new CreateIndexRequest(index);
            client.indices().create(createIndexRequest);
        }
        client.close();
    }

    private boolean indexExists(String index){

        Response response = null;
        try {
            response = getClient().getLowLevelClient().performRequest("HEAD", "/"+index);

            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode == 404) {
               return false;
            } else {
                return true;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    public void updateReader(long dbid, Reader reader) throws IOException {
        RestHighLevelClient client = getClient();

        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject();
        {
            builder.startObject("permissions");
            builder.field("read",reader.getReaders());
            builder.endObject();
        }
        builder.endObject();
        UpdateRequest request = new UpdateRequest();
        request.index(INDEX_WORKSPACE);
        request.id(Long.toString(dbid));
        request.doc(builder);

        UpdateResponse updateResponse = client.update(
                request);
        String index = updateResponse.getIndex();
        String id = updateResponse.getId();
        long version = updateResponse.getVersion();
        if (updateResponse.getResult() == DocWriteResponse.Result.CREATED) {
            logger.error("object did not exist");
        } else if (updateResponse.getResult() == DocWriteResponse.Result.UPDATED) {

        } else if (updateResponse.getResult() == DocWriteResponse.Result.DELETED) {

        } else if (updateResponse.getResult() == DocWriteResponse.Result.NOOP) {

        }
        client.close();
    }
    public void index(List<NodeData> nodes) throws IOException{
        RestHighLevelClient client = getClient();

        for(NodeData nodeData: nodes) {
            NodeMetadata node = nodeData.getNodeMetadata();
            String storeRefProtocol = Tools.getProtocol(node.getNodeRef());
            String storeRefIdentifier = Tools.getIdentifier(node.getNodeRef());
            String storeRef = Tools.getStoreRef(node.getNodeRef());
                XContentBuilder builder = jsonBuilder();
                builder.startObject();
                {
                    builder.field("aclId",  node.getAclId());
                    builder.field("txnId",node.getTxnId());
                    builder.field("dbid",node.getId());

                    String id = node.getNodeRef().split("://")[1].split("/")[1];
                    builder.startObject("nodeRef")
                            .startObject("storeRef")
                                .field("protocol",storeRefProtocol)
                                .field("identifier",storeRefIdentifier)
                            .endObject()
                            .field("id",id)
                    .endObject();

                    builder.field("owner", node.getOwner());
                    builder.field("type",node.getType());

                    //valuespaces
                    if(nodeData.getValueSpaces().size() > 0){
                        builder.startObject("i18n");
                        for(Map.Entry<String,Map<String,List<String>>> entry : nodeData.getValueSpaces().entrySet())      {
                            String language = entry.getKey().split("-")[0];
                            builder.startObject(language);
                            for(Map.Entry<String,List<String>> valuespace : entry.getValue().entrySet() ){

                                String key = Constants.getValidLocalName(valuespace.getKey());
                                if(key != null) {
                                    builder.field(key, valuespace.getValue());
                                }else{
                                    logger.error("unknown valuespace property: " + valuespace.getKey());
                                }
                            }
                            builder.endObject();
                        }
                        builder.endObject();
                    }

                    if(node.getPaths() != null && node.getPaths().size() > 0){
                        String[] pathEle = node.getPaths().get(0).getApath().split("/");
                        builder.field("path", Arrays.copyOfRange(pathEle,1,pathEle.length - 1));
                    }

                    builder.startObject("permissions");
                    builder.field("read",nodeData.getReader().getReaders());
                    builder.endObject();

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
                    LinkedHashMap content = (LinkedHashMap)node.getProperties().get("{http://www.alfresco.org/model/content/1.0}content");
                    if(content != null){

                        builder.startObject("content");
                        builder.field("contentId", content.get("contentId"));
                        builder.field("encoding", content.get("encoding"));
                        builder.field("locale", content.get("locale"));
                        builder.field("mimetype", content.get("mimetype"));
                        builder.field("size", content.get("size"));
                        if(nodeData.getFullText() != null){
                            builder.field("fulltext", nodeData.getFullText());
                        }
                        builder.endObject();
                    }



                    builder.startObject("properties");
                    for(Map.Entry<String, Serializable> prop : node.getProperties().entrySet()) {

                        String key = Constants.getValidLocalName(prop.getKey());
                        if(key == null){
                            logger.error("unknown namespace: " + prop.getKey());
                            continue;
                        }

                        Serializable value = prop.getValue();

                        if(prop.getValue() instanceof List){
                            List listvalue = (List)prop.getValue();

                            //i.e. cm:title
                            if( !listvalue.isEmpty() && listvalue.get(0) instanceof Map){
                                value = getMultilangValue(listvalue);
                            }

                            //i.e. cclom:general_keyword
                            if( !listvalue.isEmpty() && listvalue.get(0) instanceof List){
                                List<String> mvValue = new ArrayList<String>();
                                for(Object l : listvalue){
                                    mvValue.add(getMultilangValue((List)l));
                                }
                                if(mvValue.size() > 0){
                                    value = (Serializable) mvValue;
                                }
                            }
                        }
                        if("cm:modified".equals(key) || "cm:created".equals(key)){

                            if(prop.getValue() != null) {
                                value = Date.from(Instant.parse((String) prop.getValue())).getTime();
                            }
                        }

                        //prevent Elasticsearch exception failed to parse field's value: '1-01-07T01:00:00.000+01:00'
                        if("ccm:replicationmodified".equals(key)){
                            if(prop.getValue() != null) {
                                value = prop.getValue().toString();
                            }
                        }

                        if(value != null) {

                            try {
                                builder.field(key, value);
                            }catch(MapperParsingException e){
                                logger.error("error parsing value field:" + key +"v"+value,e);
                            }
                        }
                    }
                    builder.endObject();

                    builder.field("aspects", node.getAspects());

                }
                builder.endObject();


                IndexRequest indexRequest = new IndexRequest(INDEX_WORKSPACE).type(TYPE)
                        .id(Long.toString(node.getId())).source(builder);

                IndexResponse indexResponse = client.index(indexRequest);
                String index = indexResponse.getIndex();
                String id = indexResponse.getId();
                if (indexResponse.getResult() == DocWriteResponse.Result.CREATED) {
                    logger.debug("created node in elastic:" + node);
                } else if (indexResponse.getResult() == DocWriteResponse.Result.UPDATED) {
                    logger.debug("updated node in elastic:" + node);
                }
                ReplicationResponse.ShardInfo shardInfo = indexResponse.getShardInfo();
                if (shardInfo.getTotal() != shardInfo.getSuccessful()) {
                    logger.debug("shardInfo.getTotal() "+shardInfo.getTotal() +"!="+ "shardInfo.getSuccessful():" +shardInfo.getSuccessful());
                }
                if (shardInfo.getFailed() > 0) {
                    for (ReplicationResponse.ShardInfo.Failure failure :
                            shardInfo.getFailures()) {
                        String reason = failure.reason();
                        logger.error(failure.nodeId() +" reason:"+reason);
                    }
                }


        }

        client.close();

    }

    private String getMultilangValue(List listvalue){
        if(listvalue.size() > 1){
            // find german value i.e for Documents/Images edu folder
            String value = null;
            String defaultValue = null;

            for(Object o : listvalue){
                Map m = (Map)o;
                //default is {key="locale",value="de"},{key="value",value="Deutsch"}
                if(m.size() > 2){
                    throw new RuntimeException("language map has only one value");
                }
                defaultValue = (String)m.get("value");
                if("de".equals(m.get("locale"))){
                    value = (String)m.get("value");
                }
            }
            if(value == null) value = defaultValue;
            return value;
        }else {
            Map multilangValue = (Map) listvalue.get(0);
            return (String) multilangValue.get("value");
        }
    }

    public void setTransaction(long txnCommitTime, long transactionId) throws IOException {

        XContentBuilder builder = jsonBuilder();
        builder.startObject();
        {
            builder.field("txnId", transactionId);
            builder.field("txnCommitTime",txnCommitTime);
        }
        builder.endObject();

        setNode(INDEX_TRANSACTIONS, ID_TRANSACTION,builder);
    }

    private void setNode(String index, String id, XContentBuilder builder) throws IOException {
        RestHighLevelClient client = getClient();

        IndexRequest.OpType type = null;
        GetRequest getRequest = new GetRequest().index(index).id(id);
        if(client.exists(getRequest)){
            type = IndexRequest.OpType.INDEX;
        }else{
            type = IndexRequest.OpType.CREATE;
        }
        IndexRequest indexRequest = new IndexRequest().index(index).type(TYPE)
                .id(id).source(builder);



        IndexResponse indexResponse = client.index(indexRequest);

        if (indexResponse.getResult() == DocWriteResponse.Result.CREATED) {
            logger.debug("created node in elastic:" + builder);
        } else if (indexResponse.getResult() == DocWriteResponse.Result.UPDATED) {
            logger.debug("updated node in elastic:" + builder);
        }
        ReplicationResponse.ShardInfo shardInfo = indexResponse.getShardInfo();
        if (shardInfo.getTotal() != shardInfo.getSuccessful()) {
            logger.debug("shardInfo.getTotal() "+shardInfo.getTotal() +"!="+ "shardInfo.getSuccessful():" +shardInfo.getSuccessful());
        }
        if (shardInfo.getFailed() > 0) {
            for (ReplicationResponse.ShardInfo.Failure failure :
                    shardInfo.getFailures()) {
                String reason = failure.reason();
                logger.error(failure.nodeId() +" reason:"+reason);
            }
        }
        client.close();
    }

    private GetResponse get(String index, String id) throws IOException {
        RestHighLevelClient client = getClient();
        GetRequest getRequest = new GetRequest();
        getRequest.index(index);
        getRequest.id(id);
        GetResponse resp = client.get(getRequest);
        client.close();
        return resp;
    }

    public Tx getTransaction() throws IOException {

        GetResponse resp = this.get(INDEX_TRANSACTIONS, ID_TRANSACTION);
        Tx transaction = null;
        if(resp.isExists()) {

            transaction = new Tx();
            transaction.setTxnCommitTime((Long) resp.getSource().get("txnCommitTime"));
            transaction.setTxnId((Integer) resp.getSource().get("txnId"));
        }

        return transaction;
    }


    public void setACLChangeSet(long aclChangeSetTime, long aclChangeSetId) throws IOException {
        XContentBuilder builder = jsonBuilder();
        builder.startObject();
        {
            builder.field("aclChangeSetId", aclChangeSetId);
            builder.field("aclChangeSetCommitTime",aclChangeSetTime);
        }
        builder.endObject();

        setNode(INDEX_TRANSACTIONS, ID_ACL_CHANGESET,builder);
    }

    public ACLChangeSet getACLChangeSet() throws IOException {
        GetResponse resp = this.get(INDEX_TRANSACTIONS, ID_ACL_CHANGESET);

        ACLChangeSet aclChangeSet = null;
        if(resp.isExists()) {
            aclChangeSet = new ACLChangeSet();
            aclChangeSet.setAclChangeSetCommitTime((Long) resp.getSource().get("aclChangeSetCommitTime"));
            aclChangeSet.setAclChangeSetId((int)resp.getSource().get("aclChangeSetId"));
        }

        return aclChangeSet;
    }


    public void delete(List<Node> nodes) throws IOException {
        RestHighLevelClient client = getClient();

        for(Node node : nodes){
            DeleteRequest request = new DeleteRequest();
            request.index(INDEX_WORKSPACE);
            request.type(TYPE);
            request.id(Long.toString(node.getId()));

            DeleteResponse deleteResponse = client.delete(
                    request);

            String index = deleteResponse.getIndex();
            String id = deleteResponse.getId();
            long version = deleteResponse.getVersion();
            ReplicationResponse.ShardInfo shardInfo = deleteResponse.getShardInfo();
            if (shardInfo.getTotal() != shardInfo.getSuccessful()) {
                logger.debug("shardInfo.getTotal() != shardInfo.getSuccessful() index:" + index +" id:"+id + " version:"+version);
            }
            if (shardInfo.getFailed() > 0) {
                for (ReplicationResponse.ShardInfo.Failure failure :
                        shardInfo.getFailures()) {
                    String reason = failure.reason();
                    logger.error(reason + "index:" + index +" id:"+id +" version:"+version);
                }
            }
        }

        client.close();
    }

    /**
     * @TODO
     * @throws IOException
     */
    public void createIndex() throws IOException {
        try {
            RestHighLevelClient client = getClient();


            CreateIndexRequest indexRequest = new CreateIndexRequest(INDEX_WORKSPACE);

            XContentBuilder builder = XContentFactory.jsonBuilder();
            builder.startObject();
            {

                builder.startObject("properties");
                {
                    builder.startObject("aclId").field("type", "long").endObject();
                    builder.startObject("txnId").field("type", "long").endObject();
                    builder.startObject("dbid").field("type", "long").endObject();
                    builder.startObject("nodeRef").field("type", "text").endObject();
                    builder.startObject("owner").field("type", "text").endObject();
                    builder.startObject("type").field("type", "text").endObject();
                    builder.startObject("readers").field("type", "text").endObject();
                    //builder.startObject("name").field("type", "text").endObject();
                    //builder.startObject("keywords").field("type", "keyword").endObject();
                }
                builder.endObject();
            }
            builder.endObject();

            indexRequest.mapping(null,builder);



        }catch(Exception e) {
            // index already exists
            // throw new RuntimeException("Elastic search init failed",e);
        }
    }

    public SearchHits searchForAclId(long acl) throws IOException {
        return this.search(INDEX_WORKSPACE, QueryBuilders.termQuery("aclId", acl), 0, 10000);
    }

    public SearchHits search(String index, QueryBuilder queryBuilder, int from, int size) throws IOException {
        RestHighLevelClient client = getClient();
        SearchRequest searchRequest = new SearchRequest(index);
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(queryBuilder);
        searchSourceBuilder.from(from);
        searchSourceBuilder.size(size);
        searchRequest.source(searchSourceBuilder);
        SearchResponse searchResponse = client.search(searchRequest);
        client.close();
        return searchResponse.getHits();
    }

    RestHighLevelClient getClient(){
      return new RestHighLevelClient(
                RestClient.builder(
                        new HttpHost(elasticHost, elasticPort, elasticProtocol)
                        //,new HttpHost("localhost", 9201, "http")
                ));
    };



}
