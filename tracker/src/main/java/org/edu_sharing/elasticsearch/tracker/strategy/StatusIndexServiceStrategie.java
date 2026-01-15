package org.edu_sharing.elasticsearch.tracker.strategy;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;

@RequiredArgsConstructor
public class StatusIndexServiceStrategie implements TrackerStrategy {
    private final StatusIndexService<Tx> transactionStateService;

    @SneakyThrows
    @Override
    public Long getLimit() {
        return transactionStateService.getState().getTxnCommitTime();
    }
}
