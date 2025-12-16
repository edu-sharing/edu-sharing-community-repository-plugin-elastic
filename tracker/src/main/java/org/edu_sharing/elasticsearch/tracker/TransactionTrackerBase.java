package org.edu_sharing.elasticsearch.tracker;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.*;
import org.edu_sharing.elasticsearch.edu_sharing.client.EduSharingClient;
import org.edu_sharing.elasticsearch.elasticsearch.core.AuthorityService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.metric.MetricContextHolder;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.strategy.TrackerStrategy;
import org.edu_sharing.repository.client.tools.CCConstants;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Collectors;

import static org.edu_sharing.elasticsearch.metric.MetricContextHolder.MetricContext.PROGRESS_FACTOR;

@Slf4j
public abstract class TransactionTrackerBase implements TransactionTracker {

    @Getter
    @Setter(AccessLevel.PACKAGE)
    protected AlfrescoWebscriptClient alfClient;

    @Getter
    @Setter(AccessLevel.PACKAGE)
    protected WorkspaceService workspaceService;

    @Getter
    @Setter(AccessLevel.PACKAGE)
    protected AuthorityService authorityService;

    @Getter
    @Setter(AccessLevel.PACKAGE)
    protected EduSharingClient eduSharingClient;

    @Getter
    @Setter(AccessLevel.PACKAGE)
    protected StatusIndexService<Tx> transactionStateService;

    @Setter(AccessLevel.PACKAGE)
    private TrackerStrategy trackerStrategy;

    @Setter
    Integer threadCount = 4;

    @Setter
    int numberOfTransactions = 200;

    @Setter(AccessLevel.PROTECTED)
    protected List<String> includeNodeTypes = null;

    @Setter(AccessLevel.PROTECTED)
    protected List<String> excludeNodeTypes = null;

    protected ForkJoinPool threadPool;

    protected TransactionTrackerBase() {
    }

    public void init() {
        threadPool = new ForkJoinPool(threadCount);
    }

    @Override
    public State track() {
        try {
            eduSharingClient.refreshValuespaceCache();
            Tx txn = transactionStateService.getState();
            if (txn == null) {
                log.info("no transaction processed");
            }

            long lastTransactionId = Optional.ofNullable(txn).map(Tx::getTxnId).orElse(0L);
            long lastTransactionTimestamp = Optional.ofNullable(txn).map(Tx::getTxnCommitTime).orElse(0L);
            log.info("starting lastTransactionId: {} timestamp: {} numberOfTransactions: {}", lastTransactionId, lastTransactionTimestamp, numberOfTransactions);


            long nextTransactionId = lastTransactionId + 1;
            Transactions transactions;
            if(lastTransactionTimestamp > 0) {
                transactions = alfClient.getTransactions(null, null, lastTransactionTimestamp + 1, null, numberOfTransactions);
            } else {
                log.warn("no last transaction timestamp, need to fallback to id mode, txnId {}", nextTransactionId);
                transactions = alfClient.getTransactions(nextTransactionId, null, null, null, numberOfTransactions);
            }

            long maxTrackerTxnId = transactions.getMaxTxnId();

            if (transactions.getTransactions().isEmpty()) {
                MetricContextHolder.getTransactionContext().getProgress().set((long) (100 * PROGRESS_FACTOR));
                MetricContextHolder.getTransactionContext().getTimestamp().set(System.currentTimeMillis());
                if (trackerStrategy.getLimit() != null) {
                    log.info("max transaction limit by strategy reached: {} / {}", maxTrackerTxnId, trackerStrategy.getLimit());
                    return State.FINISHED;
                } else {
                    log.info("index is up to date getMaxTxnId(): {} lastTransactionId: {}", maxTrackerTxnId, lastTransactionId);
                    return State.FINISHED;
                }
            }

            List<Long> transactionIds = transactions.getTransactions()
                    .stream()
                    .map(Transaction::getId)
                    .collect(Collectors.toList());
            log.info("got " + transactionIds.size() + " transactions last:" + transactionIds.get(transactionIds.size() - 1));

            GetNodeParamExtension getNodeParam = new GetNodeParamExtension();

            if(this.includeNodeTypes != null && !this.includeNodeTypes.isEmpty()) {
                List<String> list = this.includeNodeTypes.stream()
                        .map(CCConstants::getValidGlobalName)
                        .filter(Objects::nonNull).toList();
                if(!list.isEmpty()) getNodeParam.setIncludeNodeTypes(list);
            }
            if(this.excludeNodeTypes != null && !this.excludeNodeTypes.isEmpty()) {
                List<String> list = this.excludeNodeTypes.stream()
                        .map(CCConstants::getValidGlobalName)
                        .filter(Objects::nonNull).toList();
                if(!list.isEmpty()) getNodeParam.setExcludeNodeTypes(list);
            }

            getNodeParam.setTxnIds(transactionIds);
            List<Node> nodes = alfClient.getNodes(getNodeParam);
            log.info("got " + nodes.size() + " nodes");

            eduSharingClient.refreshValuespaceCache();

            // index nodes
            trackNodes(nodes);

            //remember processed transaction
            Transaction last = transactions.getTransactions().stream().max((a, b) -> Long.compare(
                    a.getCommitTimeMs(), b.getCommitTimeMs()
            )).get();
            commit(transactionStateService, new Tx(last.getId(), last.getCommitTimeMs()));

            // log progress
            MetricContextHolder.getTransactionContext().getProgress().set((long) (calcProgress(transactions, transactionIds) * PROGRESS_FACTOR));
            MetricContextHolder.getTransactionContext().getTimestamp().set(lastTransactionTimestamp);
            log.info("finished {}% ({} hours behind), lastTransactionId: {} transactions: {} nodes: {} Stack size: {}",
                    Tools.df.format(calcProgress(transactions, transactionIds)),
                    Tools.df.format((System.currentTimeMillis() - lastTransactionTimestamp) / 1000.0 / 60 / 24),
                    last,
                    Arrays.toString(transactionIds.toArray()),
                    nodes.size(),
                    Thread.currentThread().getStackTrace().length);

            return State.INPROGRESS;
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return State.EXCEPTION;
        }
    }

    private void commit(StatusIndexService<Tx> transactionStateService, Tx tx) throws IOException {
        log.info("safe transactionId {}", tx.getTxnId());
        transactionStateService.setState(tx);
    }

    private Double calcProgress(Transactions transactions, List<Long> transactionIds) {
        Long last = transactionIds.get(transactionIds.size() - 1);
        return (double) last / (double) transactions.getMaxTxnId() * 100.0d;
    }

    public abstract void trackNodes(List<Node> nodes) throws IOException;
}
