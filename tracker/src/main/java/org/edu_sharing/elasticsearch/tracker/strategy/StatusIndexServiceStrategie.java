package org.edu_sharing.elasticsearch.tracker.strategy;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceInterface;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;

@RequiredArgsConstructor
public class StatusIndexServiceStrategie implements TrackerStrategy {
    private final StatusIndexServiceInterface<Tx> transactionStateService;

    @SneakyThrows
    @Override
    public Long getLimit() {
        return transactionStateService.getState().getTxnCommitTime();
    }
}
