package org.edu_sharing.elasticsearch.tracker.core;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.common.util.StringUtils;
import org.edu_sharing.elasticsearch.alfresco.client.*;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.AuthorityService;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceInterface;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.edu_sharing.elasticsearch.tracker.strategy.DependentStatusIndexServiceStrategie;
import org.edu_sharing.elasticsearch.tracker.utils.ThreadUtil;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import static org.edu_sharing.elasticsearch.metric.MetricContext.PROGRESS_FACTOR;

@Slf4j
public abstract class AbstractAlfTransactionTracker<PROPS extends AlfTransactionTrackerProperties> extends AbstractTracker<PROPS, Tx> {


    @Setter(onMethod_ = @Autowired)
    protected AlfrescoApi alfClient;
    @Setter(onMethod_ = @Autowired)
    protected WorkspaceService workspaceService;
    @Setter(onMethod_ = @Autowired)
    protected AuthorityService authorityService;


    @Setter(onMethod_ = @Autowired)
    protected EduSharingService eduSharingService;

    protected ThreadUtil threadUtil;

    public AbstractAlfTransactionTracker(PROPS props) {
        super(props);
        this.threadUtil = new ThreadUtil(props.getThreads()); // TODO should be injected!?
    }

    @Override
    public Class<Tx> getStatusClass() {
        return Tx.class;
    }

    @Override
    public State track(TrackingContext<Tx> trackingContext) {
        try {
            Tx txn = trackingContext.statusIndexService().getState();
            if (txn == null) {
                log.info("no transaction processed");
            }

            long lastTransactionId = Optional.ofNullable(txn).map(Tx::getTxnId).orElse(0L);
            long lastTransactionTimestamp = Optional.ofNullable(txn).map(Tx::getTxnCommitTime).orElse(0L);
            log.info("starting lastTransactionId: {} timestamp: {} numberOfTransactions: {}", lastTransactionId, lastTransactionTimestamp, props.getNumberOfTransactions());


            long nextTransactionId = lastTransactionId + 1;
            Transactions transactions;
            if (lastTransactionTimestamp > 0) {
                long endTime = trackingContext.strategy().getLimit() != null
                        ? trackingContext.strategy().getLimit()
                        : alfClient.getTransactions(0L, 1L, null, null, 1).getMaxTxnCommitTime();

                // solr-common-SqlMap.xml select_Txns ibatis template does < #{toCommitTimeExclusive} but we want it to be included
                endTime += 1;

                //check if there are txIds with the same commitTime like lastTransactionTimestamp
                Transactions tempTxs = alfClient.getTransactions(null, null, lastTransactionTimestamp, lastTransactionTimestamp + 1, props.getNumberOfTransactions());
                tempTxs.setTransactions(tempTxs.getTransactions().stream().filter(t -> t.getId() > lastTransactionId).toList());
                if (!tempTxs.getTransactions().isEmpty()) {
                    log.info("found transactions with the same commitTime: {}", Arrays.toString(tempTxs.getTransactions().stream().map(Transaction::getId).toArray()));
                    transactions = tempTxs;
                } else {
                    long fromCommitTimeMs = lastTransactionTimestamp + 1;
                    transactions = getSomeTransactions(fromCommitTimeMs, props.getTimeStep().toMillis(), props.getNumberOfTransactions(), endTime);
                }
            } else {
                log.warn("no last transaction timestamp, need to fallback to id mode, txnId {}", nextTransactionId);
                if (trackingContext.strategy() instanceof DependentStatusIndexServiceStrategie && trackingContext.strategy().getLimit() == 0) {
                    log.warn("waiting for dependent tracker");
                    return State.FINISHED;
                }
                transactions = alfClient.getTransactions(nextTransactionId, null, null, null, 1);
            }

            long maxTrackerTxnId = transactions.getMaxTxnId();

            if (transactions.getTransactions().isEmpty()) {
                boolean isBehindMainTracker = (trackingContext.strategy() instanceof DependentStatusIndexServiceStrategie && trackingContext.strategy().getLimit() < transactions.getMaxTxnCommitTime());
                if (!isBehindMainTracker) {
                    trackingContext.metricContext().getProgress().set(100 * PROGRESS_FACTOR);
                    trackingContext.metricContext().getTimestamp().set(System.currentTimeMillis());
                }

                if (trackingContext.strategy().getLimit() != null) {
                    log.info("max transaction limit by strategy reached: {} / {}", maxTrackerTxnId, trackingContext.strategy().getLimit());
                } else {
                    log.info("index is up to date getMaxTxnId(): {} lastTransactionId: {}", maxTrackerTxnId, lastTransactionId);
                }
                return State.FINISHED;
            }

            List<Long> transactionIds = transactions.getTransactions()
                    .stream()
                    .map(Transaction::getId)
                    .collect(Collectors.toList());
            log.info("got {} transactions last:{}", transactionIds.size(), transactionIds.get(transactionIds.size() - 1));

            GetNodeParamExtension getNodeParam = new GetNodeParamExtension();

            if (this.props.getIncludeNodeTypes() != null && !this.props.getIncludeNodeTypes().isEmpty()) {
                List<String> list = this.props.getIncludeNodeTypes().stream()
                        .map(CCConstants::getValidGlobalName)
                        .filter(Objects::nonNull).toList();
                if (!list.isEmpty()) getNodeParam.setIncludeNodeTypes(list);
            }
            if (this.props.getExcludeNodeTypes() != null && !this.props.getExcludeNodeTypes().isEmpty()) {
                List<String> list = this.props.getExcludeNodeTypes().stream()
                        .map(CCConstants::getValidGlobalName)
                        .filter(Objects::nonNull).toList();
                if (!list.isEmpty()) getNodeParam.setExcludeNodeTypes(list);
            }
            if (this.props.getIncludeAspects() != null && !this.props.getIncludeAspects().isEmpty()) {
                List<String> list = this.props.getIncludeAspects().stream()
                        .map(CCConstants::getValidGlobalName)
                        .filter(Objects::nonNull).toList();
                if (!list.isEmpty()) getNodeParam.setIncludeAspects(list);
            }
            if (this.props.getExcludeAspects() != null && !this.props.getExcludeAspects().isEmpty()) {
                List<String> list = this.props.getExcludeAspects().stream()
                        .map(CCConstants::getValidGlobalName)
                        .filter(Objects::nonNull).toList();
                if (!list.isEmpty()) getNodeParam.setExcludeAspects(list);
            }

            if (!StringUtils.isEmpty(this.props.getStoreIdentifier())) {
                getNodeParam.setStoreIdentifier(props.getStoreIdentifier());
            }

            if(!StringUtils.isEmpty(this.props.getStoreProtocol())){
                getNodeParam.setStoreProtocol(props.getStoreProtocol());
            }

            getNodeParam.setTxnIds(transactionIds);
            List<Node> nodes = alfClient.getNodes(getNodeParam);
            log.info("got {} nodes", nodes.size());

            // index nodes
            trackNodes(nodes);

            //remember processed transaction
            Transaction last = transactions.getTransactions().stream().max(Comparator
                    .comparingLong(Transaction::getCommitTimeMs)
                    .thenComparingLong(Transaction::getId)
            ).orElseThrow();
            commit(trackingContext.statusIndexService(), new Tx(last.getId(), last.getCommitTimeMs()));

            // log progress
            Double progress = calcProgress(transactions, transactionIds);
            trackingContext.metricContext().getProgress().set((long) (progress * PROGRESS_FACTOR));
            trackingContext.metricContext().getTimestamp().set(lastTransactionTimestamp);


            log.info("finished {}% ({} hours behind), lastTransactionId: {} transactions: {} nodes: {} Stack size: {}",
                    Tools.df.format(progress),
                    Tools.df.format((System.currentTimeMillis() - lastTransactionTimestamp) / 1000.0 / 60 / 24),
                    last,
                    Arrays.toString(transactionIds.toArray()),
                    nodes.size(),
                    Thread.currentThread().getStackTrace().length);

            return State.IN_PROGRESS;
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return State.EXCEPTION;
        }
    }

    /**
     * inspired by alfresco MetadataTracker.getSomeTransactions
     */
    public Transactions getSomeTransactions(Long fromCommitTime, long timeStep, int maxResults, long endTime) {
        Transactions transactions;
        long startTime = fromCommitTime;
        do {
            long toCommitTime = startTime + timeStep;
            if (toCommitTime > endTime) {
                toCommitTime = endTime;
            }
            transactions = alfClient.getTransactions(null, null, startTime, toCommitTime, maxResults);
            startTime = toCommitTime;
            if (transactions.getTransactions().isEmpty()) {
                long nextFromCommitTime = alfClient.getNextCommitTime(startTime).getNextTransactionCommitTimeMs();
                if (nextFromCommitTime != -1 && nextFromCommitTime < endTime) {
                    long nextToCommitTime = nextFromCommitTime + timeStep;
                    if (nextToCommitTime > endTime) {
                        nextToCommitTime = endTime;
                    }
                    log.info("Advancing transactions from {} to {}", startTime, nextFromCommitTime);
                    transactions = alfClient.getTransactions(null, null, nextFromCommitTime, nextToCommitTime, maxResults);
                }
            }
        } while (transactions.getTransactions().isEmpty() && (startTime < endTime));
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

    /**
     * Filters the raw transaction nodes down to the ones this tracker should actually write, and
     * deduplicates them by UUID.
     * <p>
     * Store filtering is not this method's job: it is done server-side by {@code track()} passing
     * {@code storeProtocol}/{@code storeIdentifier} to {@code alfClient.getNodes(...)} (see above), the
     * same mechanism {@code content}/{@code preview}/{@code collection}/{@code statisticsalfresco}
     * already use - so {@code nodes} here never contains a store this tracker did not ask for.
     * <p>
     * Dedup still must key on the UUID rather than on {@code Node} itself, though: moving a node to the
     * trash and restoring it can assign it a new Alfresco DBID for the same UUID, so a batch spanning
     * both events can contain two {@code Node} entries for that UUID with different DBIDs (in the same
     * store, sequentially - a node is never in two stores at once). Since {@code Node.equals}/
     * {@code hashCode} compare the DBID, keying dedup on {@code Node} would not recognize those as
     * duplicates: both would independently trigger a live fetch/write for the same UUID - redundant
     * work, and two writers racing the same Elasticsearch {@code _id} (that {@code _id} is the UUID, via
     * {@code Tools.getUUID} - not the DBID). Neither duplicate is "stale" in that fetch, though:
     * SuggestionTracker/RelationTracker only use the surviving {@code Node} to derive the UUID, then
     * read the current suggestions/relations live from the repository, so either duplicate would
     * produce an identical result. Keeping the highest {@code txnId} is therefore just a deterministic
     * tie-break, not a correctness requirement.
     */
    protected List<Node> filterIndexableNodes(List<Node> nodes) {
        Map<String, Node> byUuid = new LinkedHashMap<>();
        int skippedDeleted = 0;
        for (Node node : nodes) {
            if ("d".equals(node.getStatus())) {
                skippedDeleted++;
                continue;
            }
            byUuid.merge(Tools.getUUID(node.getNodeRef()), node,
                    (a, b) -> a.getTxnId() >= b.getTxnId() ? a : b);
        }
        int deduplicated = nodes.size() - skippedDeleted - byUuid.size();
        log.info("filtered nodes: in={} out={} skippedDeleted={} deduplicated={}",
                nodes.size(), byUuid.size(), skippedDeleted, deduplicated);
        return List.copyOf(byUuid.values());
    }

}
