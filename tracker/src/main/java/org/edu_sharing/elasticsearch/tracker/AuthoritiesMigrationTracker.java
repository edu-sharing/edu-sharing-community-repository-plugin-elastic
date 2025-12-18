package org.edu_sharing.elasticsearch.tracker;

import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.GetNodeMetadataParam;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeData;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.repository.client.tools.CCConstants;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class AuthoritiesMigrationTracker extends DefaultTransactionTracker{

    public AuthoritiesMigrationTracker(){
        super();
    }

    @Override
    public void init() {
        super.init();
        // cleanup exclude filter, it seems that combination of include/exclude does not work
        this.excludeNodeTypes.clear();
        this.setIncludeNodeTypes(List.of(CCConstants.CM_TYPE_PERSON,CCConstants.CM_TYPE_AUTHORITY_CONTAINER));
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

    public void indexNodesMetadata(List<NodeMetadata> nodeData) throws IOException {
        Map<Boolean, List<NodeMetadata>> partitioned = nodeData.stream()
                .collect(Collectors.partitioningBy(
                        n -> List.of("cm:person", "cm:authorityContainer").contains(n.getType())
                ));

        nodeData = partitioned.get(true);
        List<NodeMetadata> otherNodes = partitioned.get(false);
        if(!otherNodes.isEmpty()){
            String otherNodesString = otherNodes.stream()
                    .map(n -> n.getNodeRef() + ":" + n.getType())
                    .collect(Collectors.joining(","));
            log.warn("no person or authority nodes:" + otherNodesString);
        }
        // authorities
        log.info("start index Authorities/Persons:"+nodeData.size());
        List<NodeData> toIndexAuthorities = prepareAuthorities(nodeData);
        authorityService.index(toIndexAuthorities);
        log.info("finished index Authorities/Persons:"+nodeData.size());
    }
}
