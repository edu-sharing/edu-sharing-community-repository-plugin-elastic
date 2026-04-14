package org.edu_sharing.elasticsearch.tracker.strategy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class MaxCommitTimeStrategy implements TrackerStrategy {

    private final long maxCommitTime;

    @Override
    public Long getLimit() {
        return maxCommitTime;
    }
}
