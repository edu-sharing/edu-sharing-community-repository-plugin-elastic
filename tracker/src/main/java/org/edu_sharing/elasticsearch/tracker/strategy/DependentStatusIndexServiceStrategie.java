package org.edu_sharing.elasticsearch.tracker.strategy;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceInterface;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;

import java.io.IOException;
import java.util.List;

/**
 * This strategy evaluates the minimum transaction commit time across all provided transaction state services.
 * </br>
 * Responsibilities:
 * - Utilizes the `transactionStateService` for collecting state information.
 * - Calls `getState` on each service to retrieve the `Tx` object and extracts its `txnCommitTime`.
 * - Returns the smallest transaction commit time found or `Long.MIN_VALUE` if no transactions are available.
 * </br>
 * Dependencies:
 * - List of StatusIndexServiceInterface instances for fetching state data.
 * - The `Tx` class, which represents transaction data containing `txnCommitTime`.
 */
@RequiredArgsConstructor
public class DependentStatusIndexServiceStrategie implements TrackerStrategy {
    private final List<StatusIndexServiceInterface<Tx>> dependentTransactionStateServices;

    @Override
    public Long getLimit() {
        return dependentTransactionStateServices.stream()
                .map(x-> {
                    try {
                        return x.getState();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                })
                .map(Tx::getTxnCommitTime)
                .min(Long::compare)
                .orElseThrow(() -> new RuntimeException("No dependent transactions found!"));
    }
}
