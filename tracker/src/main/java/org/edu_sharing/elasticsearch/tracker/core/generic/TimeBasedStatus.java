package org.edu_sharing.elasticsearch.tracker.core.generic;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.edu_sharing.elasticsearch.tracker.strategy.CommitTimeStatus;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimeBasedStatus implements CommitTimeStatus {
    private Long commitTime;
    /**
     * Tiebreaker id of the last processed entry at commitTime, for data sources where multiple
     * entries can share the exact same timestamp. Null if the tracker's data source has no such
     * id, or no entry has been processed yet.
     */
    private Long lastId;

    public TimeBasedStatus(Long commitTime) {
        this(commitTime, null);
    }
}
