package org.edu_sharing.elasticsearch.tracker;

import org.edu_sharing.elasticsearch.alfresco.client.*;

import java.util.List;

public class AlfrescoApiMock implements AlfrescoApi {

    Transactions data;

    public AlfrescoApiMock(Transactions data){
        this.data = data;
    }

    @Override
    public Transactions getTransactions(Long minTxnId, Long maxTxnId, Long fromCommitTime, Long toCommitTime, int maxResults) {
        List<Transaction> filtered = data.getTransactions().stream()
                .filter(t -> t.getCommitTimeMs() >= fromCommitTime && t.getCommitTimeMs() < toCommitTime)
                .limit(maxResults)
                .toList();
        Transactions tx = new Transactions();
        tx.setTransactions(filtered);
        return tx;
    }

    @Override
    public NextCommitTime getNextCommitTime(long fromCommitTime) {
        return data.getTransactions().stream()
                .map(Transaction::getCommitTimeMs)
                .filter(t -> t > fromCommitTime)
                .min(Long::compareTo)
                .map(NextCommitTime::new)
                .orElse(new NextCommitTime(-1));
    }

    @Override
    public List<Node> getNodes(GetNodeParam p) {
        return List.of();
    }

    @Override
    public List<NodeData> getNodeData(List<NodeMetadata> nodes) {
        return List.of();
    }

    @Override
    public List<NodeMetadata> getNodeMetadata(List<Node> nodes) {
        return List.of();
    }

    @Override
    public List<NodeData> getNodeData(List<NodeMetadata> nodes, FetchParameters parameters) {
        return List.of();
    }
}
