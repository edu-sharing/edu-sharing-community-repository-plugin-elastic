package org.edu_sharing.elasticsearch.tracker.acl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.*;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.AclTx;
import org.edu_sharing.elasticsearch.tracker.core.AbstractTracker;
import org.edu_sharing.elasticsearch.tracker.core.TrackingContext;
import org.edu_sharing.elasticsearch.tracker.strategy.DependentStatusIndexServiceStrategie;
import org.edu_sharing.elasticsearch.tracker.utils.Partition;
import org.edu_sharing.elasticsearch.tracker.utils.ThreadUtil;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

import static org.edu_sharing.elasticsearch.metric.MetricContext.PROGRESS_FACTOR;


@Slf4j
@Component
public class AclTracker extends AbstractTracker<AclTrackerProperties, AclTx> {

    private final AlfrescoWebscriptClient alfClient;
    private final WorkspaceService workspaceService;

    private final AclTrackerProperties trackerProperties;

    private ThreadUtil threadUtil;

    public AclTracker(AclTrackerProperties aclTrackerProperties,
                      AlfrescoWebscriptClient alfClient,
                      WorkspaceService workspaceService,
                      AclTrackerProperties trackerProperties) {
        super(aclTrackerProperties);
        this.alfClient = alfClient;
        this.workspaceService = workspaceService;
        this.trackerProperties = trackerProperties;
    }


    @PostConstruct
    public void init() {
        threadUtil = new ThreadUtil(trackerProperties.getThreads());
    }


    public State track(TrackingContext<AclTx> trackingContext) {
        try {
            AclTx aclTx = trackingContext.statusIndexService().getState();
            if (aclTx != null) {
                log.info("got last aclTxn from index aclCommitTime:{} aclId{}", aclTx.getAclChangeSetCommitTime(), aclTx.getAclChangeSetId());
            }

            long lastACLChangeSetId = Optional.ofNullable(aclTx).map(AclTx::getAclChangeSetId).orElse(0L);
            long lastFromCommitTime = Optional.ofNullable(aclTx).map(AclTx::getAclChangeSetCommitTime).orElse(0L);

            long nextACLChangeSetId = lastACLChangeSetId + 1;

            log.info("starting lastACLChangeSetId:{} lastFromCommitTime:{} {}", nextACLChangeSetId, lastFromCommitTime, new Date(lastFromCommitTime));

            AclChangeSets aclChangeSets;
            if (lastFromCommitTime > 0) {
                //ToDo acl changes not synced when tx never changes a long time. only acl changes possible in edu-sharing context?
                long endTime = trackingContext.strategy().getLimit() != null
                        ? trackingContext.strategy().getLimit()
                        : alfClient.getAclChangeSets(0L, null, null, 1).getMaxChangeSetCommitTime();

                //alf sql template select_ChangeSets_Summary: toCommitTimeExclusive but we want endTime inclusive
                endTime += 1;

                //check if there are aclChangSetIds with the same commitTime like lastFromCommitTime
                AclChangeSets tempAclCs = alfClient.getAclChangeSets(null, lastFromCommitTime, lastFromCommitTime + 1, trackerProperties.getNumberOfTransactions());
                tempAclCs.setAclChangeSets(tempAclCs.getAclChangeSets().stream().filter(c -> c.getId() > lastACLChangeSetId).toList());

                if (!tempAclCs.getAclChangeSets().isEmpty()) {
                    log.info("found aclChangeSets with the same commitTime: {}", Arrays.toString(tempAclCs.getAclChangeSets().stream().map(AclChangeSet::getId).toArray()));
                    aclChangeSets = tempAclCs;
                } else {
                    aclChangeSets = getSomeAclChangeSets(lastFromCommitTime + 1, trackerProperties.getTimeStep().toMillis(), trackerProperties.getNumberOfTransactions(), endTime);
                }
            } else {
                log.warn("no last lastFromCommitTime timestamp, need to fallback to id mode, aCLChangeSetId {}", nextACLChangeSetId);
                if (trackingContext.strategy() instanceof DependentStatusIndexServiceStrategie && trackingContext.strategy().getLimit() == 0) {
                    log.warn("waiting for dependent tracker");
                    return State.FINISHED;
                }
                aclChangeSets = alfClient.getAclChangeSets(nextACLChangeSetId, null, null, trackerProperties.getNumberOfTransactions());
            }


            if (aclChangeSets.getAclChangeSets().isEmpty()) {

                boolean isBehindMainTracker = (trackingContext.strategy() instanceof DependentStatusIndexServiceStrategie && trackingContext.strategy().getLimit() < aclChangeSets.getMaxChangeSetCommitTime());
                if (!isBehindMainTracker) {
                    trackingContext.metricContext().getProgress().set(100 * PROGRESS_FACTOR);
                    trackingContext.metricContext().getTimestamp().set(System.currentTimeMillis());
                }
                log.info("index is up to date:{} lastFromCommitTime:{}", nextACLChangeSetId, lastFromCommitTime);
                return State.FINISHED;
            }

            log.info("aclChangeSets:{}", aclChangeSets.getAclChangeSets().stream().map(AclChangeSet::getId).collect(Collectors.toList()));

            log.info("resolving acl's");
            GetAclsParam param = new GetAclsParam();
            for (AclChangeSet aclChangeSet : aclChangeSets.getAclChangeSets()) {
                param.getAclChangeSetIds().add(aclChangeSet.getId());
            }

            // max 512 aclChangeSetIds allowed
            Acls acls = alfClient.getAcls(param);


            Map<Long, Acl> aclIdMap = acls.getAcls().stream()
                    .collect(Collectors.toMap(Acl::getId, accessControlList -> accessControlList));
            log.info("aclIds:{}", aclIdMap.keySet());

            log.info("resolving Readers");
            GetPermissionsParam grp = new GetPermissionsParam();
            grp.setAclIds(new ArrayList<>(aclIdMap.keySet()));
            ReadersACL readers = alfClient.getReader(grp);
            Map<Long, Reader> readersMap = readers.getAclsReaders().stream()
                    .collect(Collectors.toMap(Reader::getAclId, readersList -> readersList));

            log.info("resolving AccessControlLists");
            AccessControlLists accessControlLists = alfClient.getAccessControlLists(grp);
            Map<Long, AccessControlList> accessControlListMap = accessControlLists.getAccessControlLists().stream()
                    .collect(Collectors.toMap(AccessControlList::getAclId, accessControlList -> accessControlList));

            log.info("prepare Index Data");
            Map<Long, Map<String, List<String>>> aclPermMap = new HashMap<>();
            for (Acl acl : acls.getAcls()) {

                Reader reader = readersMap.get(acl.getId());
                if (reader.getAclId() != acl.getId()) {
                    log.warn("reader aclid:{} does not match {}", reader.getAclId(), acl.getId());
                    continue;
                }

                List<String> alfReader = reader.getReaders();
                Collections.sort(alfReader);

                //  alfresco permissions
                Map<String, List<String>> permissionsAlf = new HashMap<>();
                for (AccessControlEntry ace : accessControlListMap.get(acl.getId()).getAces()) {
                    List<String> authorities = permissionsAlf.get(ace.getPermission());
                    if (authorities == null) {
                        authorities = new ArrayList<>();
                    }
                    if (!authorities.contains(ace.getAuthority())) {
                        authorities.add(ace.getAuthority());
                    }
                    Collections.sort(authorities);
                    permissionsAlf.put(ace.getPermission(), authorities);
                }
                if (!alfReader.isEmpty()) {
                    permissionsAlf.put("read", alfReader);
                }
                //sort alf map keys:
                permissionsAlf = new TreeMap<>(permissionsAlf);
                aclPermMap.put(acl.getId(), permissionsAlf);
            }

            log.info("updating node permissions in index");

            Collection<List<Long>> partitions = Partition.getPartitions(aclPermMap.keySet(), 100);
            int pIdx = 0;
            for(List<Long> partition: partitions){
                log.info("starting partition: {}, partitionSize: {}", pIdx, partition.size());
                threadUtil.runThreaded(partition, aclId -> workspaceService.updateNodesWithAcl(aclId, aclPermMap.get(aclId)), true, true);
                pIdx++;
            }
            workspaceService.refreshWorkspace();

            AclChangeSet lastAclChangeSet = aclChangeSets.getAclChangeSets().stream().max((Comparator
                    .comparingLong(AclChangeSet::getCommitTimeMs)
                    .thenComparingLong(AclChangeSet::getId)
            )).orElseThrow();

            trackingContext.statusIndexService().setState(new AclTx(lastAclChangeSet.getId(), lastAclChangeSet.getCommitTimeMs()));


            double percentage = ((double) lastAclChangeSet.getId() - 1) / (double) aclChangeSets.getMaxChangeSetId() * 100.0d;
            trackingContext.metricContext().getProgress().set((long) (percentage * PROGRESS_FACTOR));
            trackingContext.metricContext().getTimestamp().set(lastFromCommitTime);
            DecimalFormat df = new DecimalFormat("0.00");
            log.info("finished {}% lastACLChangeSetId:{} maxChangeSetId:{}", df.format(percentage), lastAclChangeSet.getId(), aclChangeSets.getMaxChangeSetId());
            return State.IN_PROGRESS;

        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return State.EXCEPTION;
        }
    }

    /**
     * inspired by alfresco search services AclTracker.getSomeAclChangeSets
     *
     */
    private AclChangeSets getSomeAclChangeSets(Long fromCommitTime, long timeStep, int maxResults, long endTime) {
        long actualTimeStep = timeStep;
        AclChangeSets aclChangeSets;
        // step forward in time until we find something or hit the time bound
        // max id unbounded
        Long startTime = fromCommitTime == null ? Long.valueOf(0L) : fromCommitTime;
        do {
            //timeStep fix: without it AclTracker could get aclChangeSets newer than transaction progress (potential missing nodes or old aclId's)
            long toTime = startTime + actualTimeStep;
            if (toTime > endTime) {
                toTime = endTime;
            }

            aclChangeSets = alfClient.getAclChangeSets(null, startTime, toTime, maxResults);
            startTime += actualTimeStep;
            actualTimeStep *= 2;
            if (actualTimeStep > trackerProperties.getMaxTimeStep().toMillis()) {
                actualTimeStep = trackerProperties.getMaxTimeStep().toMillis();
            }
        }
        while (aclChangeSets.getAclChangeSets().isEmpty() && (startTime < endTime));

        return aclChangeSets;
    }

    @Override
    public Class<AclTx> getStatusClass() {
        return AclTx.class;
    }
}
