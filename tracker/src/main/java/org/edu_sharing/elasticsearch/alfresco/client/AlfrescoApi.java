package org.edu_sharing.elasticsearch.alfresco.client;

import java.util.List;

public interface AlfrescoApi {
    Transactions getTransactions(Long minTxnId, Long maxTxnId, Long fromCommitTime, Long toCommitTime, int maxResults);
    NextCommitTime getNextCommitTime(long fromCommitTime);

    List<Node> getNodes(GetNodeParam p);

    List<NodeData> getNodeData(List<NodeMetadata> nodes);
    List<NodeMetadata> getNodeMetadata(List<Node> nodes);
    List<NodeData> getNodeData(List<NodeMetadata> nodes, FetchParameters parameters);
}
