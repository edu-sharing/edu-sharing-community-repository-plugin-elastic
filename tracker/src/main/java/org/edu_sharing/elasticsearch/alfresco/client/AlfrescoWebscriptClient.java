package org.edu_sharing.elasticsearch.alfresco.client;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import org.apache.cxf.feature.LoggingFeature;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.ResponseProcessingException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class AlfrescoWebscriptClient {

    @Value("${alfresco.host}")
    String alfrescoHost;

    @Value("${alfresco.port}")
    String alfrescoPort;

    @Value("${alfresco.protocol}")
    String alfrescoProtocol;

    @Value("${log.requests}")
    String logRequests;

    @Value("${alfresco.readTimeout}")
    long alfrescoReadTimeout;

    @Value("${trackContent}")
    boolean trackContent;

    String URL_TRANSACTIONS = "/alfresco/service/api/solr/transactions";

    String URL_NODES_TRANSACTION = "/alfresco/s/api/solr/nodes";

    String URL_NODE_METADATA = "/alfresco/s/api/solr/metadata";

    String URL_NODE_METADATA_UUID = "/alfresco/s/api/solr/metadata/uuid?uuid={{uuid}}";

    String URL_ACL_READERS = "/alfresco/s/api/solr/aclsReaders";

    String URL_ACL_CHANGESETS = "/alfresco/s/api/solr/aclchangesets";

    String URL_ACLS = "/alfresco/s/api/solr/acls";

    String URL_CONTENT = "/alfresco/s/api/solr/textContent";

    String URL_PERMISSIONS = "/alfresco/service/api/solr/permissions";

    private static final Logger logger = LoggerFactory.getLogger(AlfrescoWebscriptClient.class);

    private Client client;

    @PostConstruct
    void init() {
        client = ClientBuilder.newBuilder()
                .connectTimeout(alfrescoReadTimeout, TimeUnit.MILLISECONDS)
                .readTimeout(alfrescoReadTimeout, TimeUnit.MILLISECONDS)
                .register(JacksonJsonProvider.class).build();
        //client.property("use.async.http.conduit", Boolean.TRUE);
        //client.property("org.apache.cxf.transport.http.async.usePolicy", AsyncHTTPConduitFactory.UseAsyncPolicy.ALWAYS);
        if (Boolean.parseBoolean(logRequests)) {
            client.register(new LoggingFeature());
        }
    }

    public List<Node> getNodes(List<Long> transactionIds) {

        String url = getUrl(URL_NODES_TRANSACTION);

        GetNodeParam p = new GetNodeParam();
        p.setTxnIds(transactionIds);
        try {
            Nodes node = client.target(url)
                    .request(MediaType.APPLICATION_JSON)
                    .post(Entity.json(p)).readEntity(Nodes.class);
            return node.getNodes();
        } catch (ResponseProcessingException e) {
            logger.warn("Could not parse nodes for all transaction ids, will fetch individually...", e);
            List<Node> result = new ArrayList<>();
            for (Long transactionId : transactionIds) {
                GetNodeParam p2 = new GetNodeParam();
                p2.setTxnIds(Collections.singletonList(transactionId));
                try {
                    Nodes node = client.target(url)
                            .request(MediaType.APPLICATION_JSON)
                            .post(Entity.json(p2)).readEntity(Nodes.class);
                    result.addAll(node.getNodes());
                } catch (ResponseProcessingException e2) {
                    logger.warn("Error reading node for transaction id " + transactionId, e2);
                }
            }
            return result;
        }
    }

    public String getTextContent(Long dbid) {
        String url = getUrl(URL_CONTENT);
        url += "?nodeId=" + dbid;
        return client.target(url)
                .request(MediaType.TEXT_PLAIN)
                .get().readEntity(String.class);
    }

    public NodeMetadatas getNodeMetadata(GetNodeMetadataParam param) {
        return this.getNodeMetadata(param, false);
    }

    public NodeMetadatas getNodeMetadata(GetNodeMetadataParam param, boolean debug) throws ResponseProcessingException {
        String url = getUrl(URL_NODE_METADATA);
        Response resp = client.target(url)
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.json(param));

        if (debug) {
            String valueAsString = resp.readEntity(String.class);
            logger.error("problems with node(s):" + valueAsString);
            return null;
        } else {
            //throws ResponseProcessingException when jaxrs data mapping fails
            return resp.readEntity(NodeMetadatas.class);
        }
    }

    public NodeMetadata getNodeMetadataUUID(String uuid) {
        String url = getUrl(URL_NODE_METADATA_UUID.replace(
                "{{uuid}}", uuid
        ));
        Response resp = client.target(url)
                .request(MediaType.APPLICATION_JSON)
                .get();

        NodeMetadataWrapper data = resp.readEntity(NodeMetadataWrapper.class);
        return data.getNode();
    }

    public List<NodeMetadata> getNodeMetadata(List<Node> nodes) {

        List<Long> dbnodeids = new ArrayList<>();
        for (Node node : nodes) {
            if(node == null) {
                logger.warn("getNodeMetadata received an null node, total list size: " + nodes.size());
                continue;
            }
            logger.debug("fetching node: " + node.getId() + "/" + node.getNodeRef());

            dbnodeids.add(node.getId());
        }

        return getNodeMetadataByIds(dbnodeids);
    }

    public List<NodeMetadata> getNodeMetadataByIds(List<Long> dbNodeIds) {
        GetNodeMetadataParam getNodeMetadataParam = new GetNodeMetadataParam();
        getNodeMetadataParam.setNodeIds(dbNodeIds);
        getNodeMetadataParam.setIncludeChildAssociations(false);
        getNodeMetadataParam.setIncludeParentAssociations(false);

        NodeMetadatas nmds;
        try {
            nmds = getNodeMetadata(getNodeMetadataParam);
            return (nmds == null) ?  new ArrayList<>() : nmds.getNodes();
        }catch (ResponseProcessingException e){
            List<NodeMetadata> fallbackResult = new ArrayList<>();
            for(Long dbid : dbNodeIds){
                GetNodeMetadataParam getNodeMetadataParamSingle = new GetNodeMetadataParam();
                getNodeMetadataParamSingle.setNodeIds(Collections.singletonList(dbid));
                getNodeMetadataParamSingle.setIncludeChildAssociations(false);
                getNodeMetadataParamSingle.setIncludeParentAssociations(false);
                try {
                    NodeMetadatas nmdsSingle = getNodeMetadata(getNodeMetadataParamSingle);
                    if(nmdsSingle != null) fallbackResult.addAll(nmdsSingle.getNodes());
                    //finally log the broken node
                }catch (ResponseProcessingException e2){
                    String url = getUrl(URL_NODE_METADATA);
                    Response resp = client.target(url)
                            .request(MediaType.APPLICATION_JSON)
                            .post(Entity.json(getNodeMetadataParamSingle));
                    String valueAsString = resp.readEntity(String.class);
                    logger.warn("problems with node:" + valueAsString, e);
                }
            }
            return fallbackResult;
        }

    }

    public List<NodeMetadata> getNodeMetadataByAllowedTypes(List<Node> nodes, final List<String> types) {
        List<Long> dbnodeids = new ArrayList<>();
        for (Node node : nodes) {
            dbnodeids.add(node.getId());
        }

        GetNodeMetadataParam getNodeMetadataParam = new GetNodeMetadataParam();
        getNodeMetadataParam.setNodeIds(dbnodeids);

        getNodeMetadataParam.setIncludeType(true);
        getNodeMetadataParam.setIncludeProperties(false);
        getNodeMetadataParam.setIncludeAspects(false);
        getNodeMetadataParam.setIncludeAclId(false);
        getNodeMetadataParam.setIncludeOwner(false);
        getNodeMetadataParam.setIncludePaths(false);
        getNodeMetadataParam.setIncludeParentAssociations(false);
        getNodeMetadataParam.setIncludeChildAssociations(false);
        getNodeMetadataParam.setIncludeNodeRef(false);
        getNodeMetadataParam.setIncludeChildIds(false);
        getNodeMetadataParam.setIncludeTxnId(false);

        //call shoulkd not lead to responseprocessing exception cause only type is returned
        NodeMetadatas nmds = getNodeMetadata(getNodeMetadataParam);
        if (nmds != null) {
            return getNodeMetadataByIds(nmds.getNodes()
                    .stream()
                    .filter(x -> types.contains(x.getType()))
                    .map(NodeMetadata::getId)
                    .collect(Collectors.toList()));

        } else return new ArrayList<>();
    }


    public List<NodeData> getNodeData(List<NodeMetadata> nodes) {
        return getNodeData(nodes, FetchParameters.ALL);
    }
    public List<NodeData> getNodeData(List<NodeMetadata> nodes, FetchParameters parameters) {
        if (nodes == null || nodes.isEmpty()) {
            return new ArrayList<>();
        }

        LinkedHashSet<Long> acls = new LinkedHashSet<>();
        for (NodeMetadata md : nodes) {
            long aclId = md.getAclId();
            acls.add(aclId);
        }
        GetPermissionsParam getPermissionsParam = new GetPermissionsParam();
        getPermissionsParam.setAclIds(new ArrayList<>(acls));
        ReadersACL readersACL = this.getReader(getPermissionsParam);
        AccessControlLists permissions = this.getAccessControlLists(getPermissionsParam);

        Map<Long, AccessControlList> permissionsMap = permissions.getAccessControlLists().stream()
                .collect(Collectors.toMap(AccessControlList::getAclId, accessControlList -> accessControlList));

        List<NodeData> result = new ArrayList<>();
        for (NodeMetadata nodeMetadata : nodes) {

            for (Reader reader : readersACL.getAclsReaders()) {
                if (nodeMetadata.getAclId() == reader.aclId) {
                    NodeData nodeData;
                    if (nodeMetadata.getType().equals("ccm:collection_proposal")) {
                        NodeDataProposal nodeDataProposal = new NodeDataProposal();
                        try {
                            GetNodeMetadataParam param = new GetNodeMetadataParam();
                            param.setNodeIds(Collections.singletonList(nodeMetadata.getId()));
                            param.setIncludeParentAssociations(true);
                            NodeMetadatas fullMetadata = getNodeMetadata(param);
                            String parent = fullMetadata.getNodes().get(0).getParentAssocs().get(0);
                            Serializable original = nodeMetadata.getProperties().
                                    get(CCConstants.getValidGlobalName(
                                                    "ccm:collection_proposal_target"
                                            )
                                    );
                            if (parent != null && original != null) {
                                // no fulltext for the original will be indexed for the proposal to save on complexity
                                try {
                                    nodeDataProposal.setOriginal(
                                            getNodeDataMinimal(getNodeMetadataUUID(Tools.getUUID((String) original)))
                                    );
                                } catch (Throwable t) {
                                    logger.info("Could not track original node for proposal " + nodeMetadata.getNodeRef() + ", original: " + original, t);
                                }
                                try {
                                    nodeDataProposal.setCollection(
                                            getNodeDataMinimal(getNodeMetadataUUID(Tools.getUUID(parent)))
                                    );
                                } catch (Throwable t) {
                                    logger.info("Could not track parent collection for proposal " + nodeMetadata.getNodeRef() + ", parent " + parent, t);
                                }
                            } else {
                                logger.warn("Collection proposal has no parent or target: " + nodeMetadata.getNodeRef());
                            }
                        }catch(Throwable t) {
                            logger.info("Could not track parent collection for proposal " + nodeMetadata.getNodeRef(), t);
                        }
                        nodeData = nodeDataProposal;
                    } else {
                        nodeData = new NodeData();
                    }
                    nodeData.setNodeMetadata(nodeMetadata);
                    nodeData.setReader(reader);
                    nodeData.setAccessControlList(permissionsMap.get(nodeMetadata.getAclId()));
                    result.add(nodeData);
                }
            }

        }


        for (NodeData nodeData : result) {
            if (parameters.content && trackContent) {
                String fullText = null;
                try {
                    fullText = getTextContent(nodeData.getNodeMetadata().getId());
                }catch(Throwable t) {
                    logger.warn("Error while fetching text content for " + nodeData.getNodeMetadata().getNodeRef(), t);
                }
                if (fullText != null) nodeData.setFullText(fullText);
            }

            if(parameters.children) {
                List<String> allowedChildTypes = new ArrayList<>();
                if ("ccm:io".equals(nodeData.getNodeMetadata().getType())) {
                    // io/file -> we allow everything
                    allowedChildTypes.add("ALL");
                } else if ("ccm:map".equals(nodeData.getNodeMetadata().getType())
                        && nodeData.getNodeMetadata().getAspects().contains("ccm:collection")) {
                    // map/folder -> we only allow specific elements relevant for maps
                    // this is to expensive here! Proposals will be individually tracked anyway
                    // allowedChildTypes.add("ccm:collection_proposal");
                }

                List<Node> children = new ArrayList<>();
                if (nodeData.getNodeMetadata().getChildIds() != null) {
                    for (Long dbid : nodeData.getNodeMetadata().getChildIds()) {
                        Node childNode = new Node();
                        childNode.setId(dbid);
                        children.add(childNode);
                    }

                    if (!children.isEmpty() && !allowedChildTypes.isEmpty()) {
                        List<NodeMetadata> nodeMetadata;
                        if (allowedChildTypes.contains("ALL")) {
                            nodeMetadata = this.getNodeMetadata(children);
                        } else {
                            nodeMetadata = this.getNodeMetadataByAllowedTypes(children, allowedChildTypes);
                        }

                        List<NodeData> childrenFiltered = getNodeData(nodeMetadata);
                        if (!childrenFiltered.isEmpty()) {
                            nodeData.getChildren().addAll(childrenFiltered);
                        }
                    }
                }
            }
        }

        return result;
    }

    /**
     * Simple version of getNodeData to fetch a single node
     * Will fetch
     * - metadata (already included in param)
     * - permissions / acls
     * Will NOT fetch
     * - preview
     * - fulltext
     */
    private NodeData getNodeDataMinimal(NodeMetadata nodeMetadata) {
        NodeData data = new NodeData();
        data.setNodeMetadata(nodeMetadata);


        LinkedHashSet<Long> acls = new LinkedHashSet<>();
        long aclId = nodeMetadata.getAclId();
        acls.add(aclId);
        GetPermissionsParam getPermissionsParam = new GetPermissionsParam();
        getPermissionsParam.setAclIds(new ArrayList<>(acls));
        ReadersACL readersACL = this.getReader(getPermissionsParam);
        AccessControlLists permissions = this.getAccessControlLists(getPermissionsParam);

        Map<Long, AccessControlList> permissionsMap = permissions.getAccessControlLists().stream()
                .collect(Collectors.toMap(AccessControlList::getAclId, accessControlList -> accessControlList));

        data.setAccessControlList(permissionsMap.get(nodeMetadata.getAclId()));
        data.setReader(readersACL.getAclsReaders().get(0));
        return data;
    }

    public ReadersACL getReader(GetPermissionsParam param) {
        String url = getUrl(URL_ACL_READERS);
        return client.target(url)
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.json(param)).readEntity(ReadersACL.class);
    }


    public Transactions getTransactions(Long minTxnId, Long maxTxnId, Long fromCommitTime, Long toCommitTime, int maxResults) {


        String url = getUrl(URL_TRANSACTIONS);

        String fromParam = "minTxnId";
        String toParam = "maxTxnId";
        Long fromValue = minTxnId;
        Long toValue = maxTxnId;
        if (fromCommitTime != null && fromCommitTime > -1) {
            fromParam = "fromCommitTime";
            toParam = "toCommitTime";
            fromValue = fromCommitTime;
            toValue = toCommitTime;
        }

        return client
                .target(url)
                .queryParam(fromParam, fromValue)
                .queryParam(toParam, toValue)
                .queryParam("maxResults", maxResults)
                .request(MediaType.APPLICATION_JSON)
                .get(Transactions.class);
    }

    public AclChangeSets getAclChangeSets(Long fromId, int maxResults) {
        String url = getUrl(URL_ACL_CHANGESETS);
        return client.target(url)
                .queryParam("fromId", fromId)
                .queryParam("maxResults", maxResults)
                .request(MediaType.APPLICATION_JSON)
                .get(AclChangeSets.class);
    }

    public Acls getAcls(GetAclsParam param) {
        String url = getUrl(URL_ACLS);

        return client.target(url)
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.json(param)).readEntity(Acls.class);
    }


    public AccessControlLists getAccessControlLists(GetPermissionsParam param) {
        String url = getUrl(URL_PERMISSIONS);
        return client.target(url)
                .request(MediaType.APPLICATION_JSON)
                .post(Entity.json(param)).readEntity(AccessControlLists.class);
    }


    private String getUrl(String path) {
        return alfrescoProtocol + "://" + alfrescoHost + ":" + alfrescoPort + path;
    }
}
