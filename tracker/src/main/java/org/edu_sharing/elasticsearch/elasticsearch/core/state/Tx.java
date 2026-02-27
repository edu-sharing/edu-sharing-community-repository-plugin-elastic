package org.edu_sharing.elasticsearch.elasticsearch.core.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.edu_sharing.elasticsearch.tracker.strategy.CommitTimeStatus;

@Data
public class Tx implements CommitTimeStatus {
    private long txnId;
    private long txnCommitTime;

    // Required for deserialization
    public Tx() {

    }
    public Tx(long txnId, long txnCommitTime) {
        this.txnId = txnId;
        this.txnCommitTime = txnCommitTime;
    }

    @Override
    @JsonIgnore
    public Long getCommitTime() {
        return txnCommitTime;
    }
}
