package org.edu_sharing.elasticsearch.tracker;

import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.GetNodeMetadataParam;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeData;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.tools.Tools;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
public class AuthoritiesMigrationTracker extends DefaultTransactionTracker{

    public AuthoritiesMigrationTracker(){
        super();
    }

    @Override
    public void trackNodes(List<Node> nodes) throws IOException {

        //filter disallowed store's
        nodes = nodes.stream()
                .filter(n -> indexStoreRefs.contains(Tools.getStoreRef(n.getNodeRef())))
                .collect(Collectors.toList());

        //filter deletes
        nodes = nodes.stream()
                .filter(n -> !n.getStatus().equals("d"))
                .collect(Collectors.toList());

        if (nodes.isEmpty()) {
            return;
        }

        Collection<List<Node>> partitions = Partition.getPartitions(nodes, fetchSizeAlfresco);

        int pIdx = 0;
        for (List<Node> partition : partitions) {
            log.info("indexNodes partition " + pIdx);
            indexNodes(partition);
            pIdx++;
        }
    }

    public void indexNodes(List<Node> nodes) throws IOException {

        List<Long> dbnodeids = nodes.stream().map(Node::getId).collect(Collectors.toList());
        GetNodeMetadataParam paramsTypeCheck = new GetNodeMetadataParam();
        paramsTypeCheck.setIncludeType(true);
        paramsTypeCheck.setIncludeAspects(false);
        paramsTypeCheck.setIncludeAclId(false);
        paramsTypeCheck.setIncludeNodeRef(false);
        paramsTypeCheck.setIncludeChildAssociations(false);
        paramsTypeCheck.setIncludeChildIds(false);
        paramsTypeCheck.setIncludeOwner(false);
        paramsTypeCheck.setIncludeParentAssociations(false);
        paramsTypeCheck.setIncludePaths(false);
        paramsTypeCheck.setIncludeTxnId(false);
        log.info("start getTypes");
        List<NodeMetadata> nodeTypes = alfClient.getNodeMetadataByIds(dbnodeids, paramsTypeCheck);
        log.info("finished getTypes");
        List<Long> filterNodeIds = filterByNodeTypes(nodeTypes).stream().map(n -> n.getId()).collect(Collectors.toList());
        log.info("getNodeMetadata start " + nodes.size());
        List<NodeMetadata> nodeData = alfClient.getNodeMetadataByIds(filterNodeIds);
        log.info("getNodeMetadata done " + nodeData.size());
        if(nodeData.size() > 0)
            indexNodesMetadata(nodeData);
    }

    public void indexNodesMetadata(List<NodeMetadata> nodeData) throws IOException {
        // authorities
        log.info("start index Authorities/Persons:"+nodeData.size());
        List<NodeData> toIndexAuthorities = prepareAuthorities(nodeData);
        authorityService.index(toIndexAuthorities);
        log.info("finished index Authorities/Persons:"+nodeData.size());
    }
}
