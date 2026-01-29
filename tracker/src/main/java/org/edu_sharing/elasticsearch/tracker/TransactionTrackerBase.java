package org.edu_sharing.elasticsearch.tracker;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.*;
import org.edu_sharing.elasticsearch.edu_sharing.client.EduSharingClient;
import org.edu_sharing.elasticsearch.elasticsearch.core.AuthorityService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceInterface;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.metric.MetricContextHolder;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.strategy.StatusIndexServiceStrategie;
import org.edu_sharing.elasticsearch.tracker.strategy.TrackerStrategy;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.springframework.util.function.ThrowingConsumer;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.edu_sharing.elasticsearch.metric.MetricContextHolder.MetricContext.PROGRESS_FACTOR;

@Slf4j
public abstract class TransactionTrackerBase implements TransactionTracker {

    @Getter
    @Setter(AccessLevel.PACKAGE)
    protected AlfrescoApi alfClient;

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
    protected StatusIndexServiceInterface<Tx> transactionStateService;

    @Setter(AccessLevel.PACKAGE)
    private TrackerStrategy trackerStrategy;

    @Setter
    Integer threadCount = 4;

    @Setter
    int numberOfTransactions = 200;

    @Getter
    @Setter
    long timeStep = TimeUnit.HOURS.toMillis(1);

    /**
     * include node types
     * make sure to use short names like ccm:io!
     */
    @Setter
    protected List<String> includeNodeTypes = null;

    /**
     * exclude node types
     * make sure to use short names like ccm:io!
     */
    @Setter
    protected List<String> excludeNodeTypes = null;

    /**
     *  make sure to use short names like ccm:collection!
     */
    @Setter
    protected List<String> excludeAspects = null;

    /**
     * make sure to use short names like ccm:collection!
     */
    @Setter
    protected List<String> includeAspects = null;

    @Setter
    protected String storeProtocol = null;

    @Setter
    protected String storeIdentifier = null;

    protected ForkJoinPool threadPool;

    @Getter
    @Setter
    protected MetricContextHolder.MetricContext metricContext = null;

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
                long endTime = trackerStrategy.getLimit() != null ? trackerStrategy.getLimit() : alfClient.getTransactions(0L,1L,null,null,1).getMaxTxnCommitTime();
                // solr-common-SqlMap.xml select_Txns ibatis template does < #{toCommitTimeExclusive} but we want it to be included
                endTime += 1;
                //check if there are txIds with the same commitTime like lastTransactionTimestamp
                Transactions tempTxs = alfClient.getTransactions(null,null,lastTransactionTimestamp,lastTransactionTimestamp +1,numberOfTransactions);
                tempTxs.setTransactions(tempTxs.getTransactions().stream().filter(t -> t.getId() > lastTransactionId).toList());
                if(!tempTxs.getTransactions().isEmpty()) {
                    log.info("found transactions with the same commitTime: {}", Arrays.toString(tempTxs.getTransactions().stream().map(Transaction::getId).toArray()));
                    transactions = tempTxs;
                }else{
                    long fromCommitTimeMs = lastTransactionTimestamp + 1;
                    transactions = getSomeTransactions(fromCommitTimeMs,timeStep,numberOfTransactions,endTime);
                }
            } else {
                log.warn("no last transaction timestamp, need to fallback to id mode, txnId {}", nextTransactionId);
                transactions = alfClient.getTransactions(nextTransactionId, null, null, trackerStrategy.getLimit(), numberOfTransactions);
            }

            long maxTrackerTxnId = transactions.getMaxTxnId();

            if (transactions.getTransactions().isEmpty()) {
                if(metricContext != null) {
                    boolean isBehindMainTracker = (trackerStrategy instanceof StatusIndexServiceStrategie && trackerStrategy.getLimit() < transactions.getMaxTxnCommitTime());
                    if(!isBehindMainTracker) {
                        metricContext.getProgress().set((long) (100 * PROGRESS_FACTOR));
                        metricContext.getTimestamp().set(System.currentTimeMillis());
                    }
                }
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
            if(this.includeAspects != null && !this.includeAspects.isEmpty()) {
                List<String> list = this.includeAspects.stream()
                        .map(CCConstants::getValidGlobalName)
                        .filter(Objects::nonNull).toList();
                if(!list.isEmpty()) getNodeParam.setIncludeAspects(list);
            }
            if(this.excludeAspects != null && !this.excludeAspects.isEmpty()) {
                List<String> list = this.excludeAspects.stream()
                        .map(CCConstants::getValidGlobalName)
                        .filter(Objects::nonNull).toList();
                if(!list.isEmpty()) getNodeParam.setExcludeAspects(list);
            }

            if(this.storeIdentifier != null && !this.storeIdentifier.isEmpty() && storeProtocol != null && !storeProtocol.isEmpty()) {
                getNodeParam.setStoreProtocol(storeProtocol);
                getNodeParam.setStoreIdentifier(storeIdentifier);
            }

            getNodeParam.setTxnIds(transactionIds);
            List<Node> nodes = alfClient.getNodes(getNodeParam);
            log.info("got " + nodes.size() + " nodes");

            eduSharingClient.refreshValuespaceCache();

            // index nodes
            trackNodes(nodes);

            //remember processed transaction
            Transaction last = transactions.getTransactions().stream().max(Comparator
                    .comparingLong(Transaction::getCommitTimeMs)
                    .thenComparingLong(Transaction::getId)
            ).get();
            commit(transactionStateService, new Tx(last.getId(), last.getCommitTimeMs()));

            // log progress
            if(metricContext != null){
                metricContext.getProgress().set((long) (calcProgress(transactions, transactionIds) * PROGRESS_FACTOR));
                metricContext.getTimestamp().set(lastTransactionTimestamp);
            }
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

    /**
     * inspired by alfresco MetadataTracker.getSomeTransactions
     *
     * @param fromCommitTime
     * @param timeStep
     * @param maxResults
     * @param endTime
     * @return
     */
    public Transactions getSomeTransactions(Long fromCommitTime, long timeStep, int maxResults, long endTime){
        Transactions transactions;
        long startTime = fromCommitTime;
        do{
            long toCommitTime = startTime + timeStep;
            if(toCommitTime > endTime){
                toCommitTime = endTime;
            }
            transactions = alfClient.getTransactions(null, null, startTime, toCommitTime, maxResults);
            startTime = toCommitTime;
            if (transactions.getTransactions().isEmpty()) {
                long nextFromCommitTime = alfClient.getNextCommitTime(startTime).getNextTransactionCommitTimeMs();
                if (nextFromCommitTime != -1 && nextFromCommitTime < endTime)
                {
                    long nextToCommitTime = nextFromCommitTime + timeStep;
                    if (nextToCommitTime > endTime){
                        nextToCommitTime = endTime;
                    }
                    log.info("Advancing transactions from {} to {}",startTime,nextFromCommitTime);
                    transactions = alfClient.getTransactions(null, null, nextFromCommitTime, nextToCommitTime, maxResults);
                }
            }
        }while (transactions.getTransactions().isEmpty() && (startTime < endTime));
        return transactions;
    }

    private void commit(StatusIndexServiceInterface<Tx> transactionStateService, Tx tx) throws IOException {
        log.info("safe transactionId {}", tx.getTxnId());
        transactionStateService.setState(tx);
    }

    protected Double calcProgress(Transactions transactions, List<Long> transactionIds) {
        Long last = transactionIds.get(transactionIds.size() - 1);
        return (double) last / (double) transactions.getMaxTxnId() * 100.0d;
    }

    public abstract void trackNodes(List<Node> nodes) throws IOException;


    public <T> void runThreaded(List<T> data, ThrowingConsumer<T> worker, boolean throwOnTimeout, boolean reThrow) throws IOException {
        List<Throwable> errors = new ArrayList<>();
        for(T d : data){
            threadPool.execute(() -> {
                try{
                    worker.acceptWithException(d);
                }catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        if (!threadPool.awaitQuiescence(10, TimeUnit.MINUTES)) {
            String msg = "Fatal error while processing data: timeout";
            log.error(msg);
            if(throwOnTimeout) throw new RuntimeException(msg);
        }
        if(!errors.isEmpty()){
            log.error("Fatal error while processing data: {}", errors);
            if(reThrow){
                if(errors.get(0) instanceof IOException){
                    throw (IOException) errors.get(0);
                }else{
                    throw new RuntimeException(errors.get(0));
                }
            }
        }
    }
}
