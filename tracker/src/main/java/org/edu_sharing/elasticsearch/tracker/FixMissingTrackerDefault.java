package org.edu_sharing.elasticsearch.tracker;

import org.edu_sharing.elasticsearch.alfresco.client.AlfrescoWebscriptClient;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.edu_sharing.client.EduSharingClient;
import org.edu_sharing.elasticsearch.elasticsearch.core.AuthorityService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.strategy.TrackerStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


//@Component
//@ConditionalOnProperty(prefix = "transaction", name="tracker", havingValue = "fix-missing")
public class FixMissingTrackerDefault extends DefaultTransactionTracker {

    // TODO
    public static String INDEX_TRANSACTIONS = "transactions_missing";

    Logger logger =  LoggerFactory.getLogger(FixMissingTrackerDefault.class);


    /**
     * create nodes missed in index
     */
    @Value("${fixmissing.repair:false}")
    boolean repair;

    File tempFile;

    Long runToTx = null;

    public FixMissingTrackerDefault(AlfrescoWebscriptClient alfClient, WorkspaceService workspaceService, AuthorityService authorityService, EduSharingClient eduSharingClient, StatusIndexService<Tx> transactionStateService, TrackerStrategy strategy) {
        super(alfClient, workspaceService,authorityService, eduSharingClient, transactionStateService, strategy);
    }


    @PostConstruct
    void createTempFile() throws IOException {
        String tmpdir = System.getProperty("java.io.tmpdir");
        String tmpFileName = "fixmissing_md_fetch_error.log";
        tempFile = new File(tmpdir + "/"+tmpFileName);

        Tx transaction = transactionStateService.getState();
        if(transaction == null && tempFile.exists()){
            logger.info("clearing error log");
            Files.delete(tempFile.toPath());
        }

        if(!tempFile.exists()){
            Files.createFile(tempFile.toPath());
        }
    }


    @Override
    public void trackNodes(List<Node> nodes) throws IOException {
        logger.info("tracking the following number of nodes:" + nodes.size());

        //filter stores
        nodes = nodes.stream()
                .filter(n -> "workspace://SpacesStore".contains(Tools.getStoreRef(n.getNodeRef())))
                .collect(Collectors.toList());

        //filter deletes
        nodes = nodes.stream()
                .filter(n -> !n.getStatus().equals("d"))
                .collect(Collectors.toList());

        logger.info("nodes cleaned up:" + nodes.size());

        //remove duplicates
        nodes = nodes.stream()
                .distinct()
                .collect(Collectors.toList());
        logger.info("nodes removed duplicates:" + nodes.size());


        //split to partion for high number of nodes (i.e 80.000)
        Collection<List<Node>> partitions = Partition.getPartitions(nodes, 500);
        int c = 0;
        int p = 0;
        for(List<Node> partition : partitions){
            logger.info("indexing main partition " + p + " partition size:"  + partition.size() +" partitions size:" +partitions.size());
            c += index(partition);
            p++;
        }
        logger.info("processed " + c +" nodes");
    }

    /**
     * returns number of nodes metadata was fetched
     */
    private int index(List<Node> nodes) throws IOException{
        long millis = System.currentTimeMillis();
        //partion for parallel processsing
        Collection<List<Node>> partitions = Partition.getPartitions(nodes, 50);
        List<NodeMetadata> tmpNodeData = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger counter = new AtomicInteger(0);

        for(List<Node> partition :  partitions) {
            threadPool.execute(() -> {
                List<NodeMetadata> tmp = alfClient.getNodeMetadata(partition);
                tmpNodeData.addAll(tmp);
                counter.addAndGet(partition.size());
            });
        }
        if(!threadPool.awaitQuiescence(10, TimeUnit.MINUTES)){
            logger.error("Fatal error while processing nodes: timeout of getNodeMetadata");
            logger.error(nodes.stream().map(Node::getNodeRef).collect(Collectors.joining(", ")));
        }
        logger.info("finished threaded getMetadata of nodes:" + nodes.size() +" nodeMetadatas:" +tmpNodeData.size());


        //sort to originally order
        List<NodeMetadata> nodeData = new ArrayList<>();
        nodes.forEach(n -> {
            NodeMetadata nodeMetadata = tmpNodeData.stream().
                    filter(nd -> nd != null && (nd.getId() == n.getId())).
                    findFirst().
                    orElse(null);
            if(nodeMetadata != null){
                nodeData.add(nodeMetadata);
            }else{
                logger.warn("nodeMetadata list is missing node with id " + n.getId());
            }
        });

        logger.info("finished sort nodes:" + nodes.size() +" nodeMetadatas:" +nodeData.size());

        logger.info("nodes getMetadata finished. nd size:" + nodeData.size() +" n size" +nodes.size() +" in " + (System.currentTimeMillis() - millis));

        List<Node> missingMetadata = new ArrayList<>();
        for(Node node : nodes){
            boolean isPresent = nodeData.stream().anyMatch(n ->  n.getId() == node.getId());
            if(!isPresent){
                logNodeProblem(node);
                logUnresolveableNode(Long.toString(node.getId()));
                missingMetadata.add(node);
            }
        }

        if(!missingMetadata.isEmpty()){
            List<String> errorNodes = new ArrayList<>();
            missingMetadata.forEach(n -> {
                errorNodes.add( n.getNodeRef() +" id:" +n.getId());
            });
            throw new RuntimeException("fetching metadata of nodes failed:" + Arrays.toString(errorNodes.toArray()));
        }


        millis = System.currentTimeMillis();
        for(NodeMetadata nodeMetadata : nodeData){
            if(isAllowedType(nodeMetadata)) {
                //2-4ms
                //GetResponse resp = elasticClient.get(ElasticsearchClient.INDEX_WORKSPACE, new Long(nodeMetadata.getId()).toString());
                if (!workspaceService.exists(Long.toString(nodeMetadata.getId()))) {
                    logNodeProblem(nodeMetadata);
                    if(repair){
                        indexNodesMetadata(List.of(nodeMetadata));
                        if("ccm:usage".equals(nodeMetadata.getType())
                                || "ccm:collection_proposal".equals(nodeMetadata.getType())){
                            logger.info("sync collections for usage:" + nodeMetadata.getId());
                            workspaceService.indexCollections(nodeMetadata);
                        }
                    }
                }
            }
        }
        logger.info("finished reindexing:" + nodeData.size() + " in " + (System.currentTimeMillis() - millis));
        return counter.get();
    }

    private void logNodeProblem(Node node){
        logger.error("Problem fetching NodeMetadata for" + " " + node.getId() +" nodeRef:"+node.getNodeRef() +" s:"+node.getStatus() +" txId:"+node.getTxnId());
    }

    private void logNodeProblem(NodeMetadata node){
        logger.error("node does not exist in elastic id:" + " " + node.getId() +" nodeRef:"+node.getNodeRef() + " type:" + node.getType() +" txId:"+node.getTxnId());
    }

    private void logUnresolveableNode(String dbid) throws IOException{
        dbid = dbid + System.lineSeparator();
        Files.write(tempFile.toPath(),dbid.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
    }

    // TODO
//    @Override
//    public long getMaxTxnId(Transactions transactions) {
//        if(runToTx == null){
//            try {
//                runToTx = transactionStateService.getState().getTxnId();
//                logger.info("running not longer than current main tracker transaction:" +runToTx);
//            } catch (IOException e) {
//               logger.error("error reaching elasticsearch");
//               return 0;
//            }
//        }
//        return runToTx;
//    }
}
