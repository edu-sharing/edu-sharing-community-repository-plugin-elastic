package org.edu_sharing.elasticsearch.elasticsearch.core.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.edu_sharing.elasticsearch.tracker.strategy.CommiteTimeStatus;

@Data
public class AclTx implements CommiteTimeStatus {

    private long aclChangeSetId;
    private long aclChangeSetCommitTime;

    // Required for deserialization
    public AclTx() {

    }

    public AclTx(long aclChangeSetId, long aclChangeSetCommitTime) {
        this.aclChangeSetId = aclChangeSetId;
        this.aclChangeSetCommitTime = aclChangeSetCommitTime;
    }

    @Override
    @JsonIgnore
    public Long getCommitTime() {
        return aclChangeSetCommitTime;
    }
}
