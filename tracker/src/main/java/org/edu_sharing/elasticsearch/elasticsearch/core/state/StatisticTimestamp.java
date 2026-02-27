package org.edu_sharing.elasticsearch.elasticsearch.core.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.edu_sharing.elasticsearch.tracker.strategy.CommitTimeStatus;


@Data
public class StatisticTimestamp implements CommitTimeStatus {
    private long statisticTimestamp;
    private boolean allInIndex;

    // Required for deserialization
    public StatisticTimestamp() {

    }

    public StatisticTimestamp(boolean allInIndex, long statisticTimestamp) {
        this.statisticTimestamp = statisticTimestamp;
        this.allInIndex = allInIndex;
    }

    @Override
    @JsonIgnore
    public Long getCommitTime() {
        return statisticTimestamp;
    }
}
