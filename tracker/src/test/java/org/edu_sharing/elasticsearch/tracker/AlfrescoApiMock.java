package org.edu_sharing.elasticsearch.tracker;

import org.edu_sharing.elasticsearch.alfresco.client.*;

import java.util.ArrayList;
import java.util.List;

public class AlfrescoApiMock implements AlfrescoApi {

    Transactions data;

    public AlfrescoApiMock(Transactions data){
        this.data = data;
    }

    @Override
    public Transactions getTransactions(Long minTxnId, Long maxTxnId, Long fromCommitTime, Long toCommitTime, int maxResults) {
        Transactions tx = new Transactions();
        List<Transaction> transactions = new ArrayList<Transaction>();
        if(fromCommitTime != null){
            transactions = data.getTransactions().stream()
                    .filter(t -> t.getCommitTimeMs() >= fromCommitTime && t.getCommitTimeMs() < toCommitTime)
                    .limit(maxResults)
                    .toList();
        }else if(minTxnId != null){
            transactions = data.getTransactions().stream()
                    // solr-common-SqlMap.xml select_Txns fromIdInclusive
                    .filter(t -> t.getId() >= minTxnId)
                    .limit(maxResults)
                    .toList();
        }
        tx.setTransactions(transactions);
        tx.setMaxTxnCommitTime(data.getMaxTxnCommitTime());
        tx.setMaxTxnId(data.getMaxTxnId());
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
