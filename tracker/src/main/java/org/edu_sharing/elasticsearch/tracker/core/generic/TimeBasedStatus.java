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
}
