package org.edu_sharing.elasticsearch.tracker;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.alfresco.client.*;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.AclTx;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.metric.MetricContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.edu_sharing.elasticsearch.metric.MetricContextHolder.MetricContext.PROGRESS_FACTOR;


@Component
@RequiredArgsConstructor
public class AclTracker {

    private final AlfrescoWebscriptClient alfClient;
    private final WorkspaceService workspaceService;



    @Value("${tracker.acl.changesets.max:200}")
    int aclchangeSetsMax;

    @Value("${tracker.timestep:36000000}")
    int timeStep;

    Logger logger = LoggerFactory.getLogger(AclTracker.class);
    private final StatusIndexService<AclTx> aclStateService;

    private static final long MAX_TIME_STEP = TimeUnit.DAYS.toMillis(32);

    private final StatusIndexService<Tx> transactionStateService;

    @Value("${threading.threadCount}")
    Integer threadCount;

    ThreadUtil threadUtil;

    @PostConstruct
    public void init() {
        threadUtil = new ThreadUtil(threadCount);
    }




    public boolean track() {
        try {
            AclTx aclTx = aclStateService.getState();
            if (aclTx != null) {
                logger.info("got last aclTxn from index aclCommitTime:" + aclTx.getAclChangeSetCommitTime() + " aclId" + aclTx.getAclChangeSetId());
            }

            long lastACLChangeSetId = Optional.ofNullable(aclTx).map(AclTx::getAclChangeSetId).orElse(0L);
            long lastFromCommitTime = Optional.ofNullable(aclTx).map(AclTx::getAclChangeSetCommitTime).orElse(0L);

            long nextACLChangeSetId = lastACLChangeSetId + 1;

            logger.info("starting lastACLChangeSetId:" + nextACLChangeSetId + " lastFromCommitTime:" + lastFromCommitTime + " " + new Date(lastFromCommitTime));

            AclChangeSets aclChangeSets;
            if(lastFromCommitTime > 0){
                //ToDo acl changes not synced when tx never changes a long time. only acl changes possible in edu-sharing context?
                Long maxChangeSetCommitTime = alfClient.getAclChangeSets(0L, null, null, 1).getMaxChangeSetCommitTime();
                Long maxTxnCommitTime = transactionStateService.getState().getTxnCommitTime();
                long endTime = (maxChangeSetCommitTime > maxTxnCommitTime) ? maxTxnCommitTime : maxChangeSetCommitTime;
                //alf sql template select_ChangeSets_Summary: toCommitTimeExclusive but we want endTime inclusive
                endTime+=1;

                //check if there are aclChangSetIds with the same commitTime like lastFromCommitTime
                AclChangeSets tempAclCs = alfClient.getAclChangeSets(null,lastFromCommitTime,lastFromCommitTime +1,aclchangeSetsMax);
                tempAclCs.setAclChangeSets(tempAclCs.getAclChangeSets().stream().filter(c -> c.getId() > lastACLChangeSetId).toList());

                if(!tempAclCs.getAclChangeSets().isEmpty()) {
                    logger.info("found aclChangeSets with the same commitTime: {}", Arrays.toString(tempAclCs.getAclChangeSets().stream().map(AclChangeSet::getId).toArray()));
                    aclChangeSets = tempAclCs;
                }else{
                    aclChangeSets = getSomeAclChangeSets(lastFromCommitTime + 1,timeStep,aclchangeSetsMax, endTime);
                }
            }else {
                logger.warn("no last lastFromCommitTime timestamp, need to fallback to id mode, aCLChangeSetId {}", nextACLChangeSetId);
                aclChangeSets = alfClient.getAclChangeSets(nextACLChangeSetId,null, null, aclchangeSetsMax);
            }


            if (aclChangeSets.getAclChangeSets().isEmpty()) {
                MetricContextHolder.getAclContext().getProgress().set((long) (100 * PROGRESS_FACTOR));
                MetricContextHolder.getAclContext().getTimestamp().set(System.currentTimeMillis());
                logger.info("index is up to date:" + nextACLChangeSetId + " lastFromCommitTime:" + lastFromCommitTime);
                return false;
            }

            logger.info("aclChangeSets:" + aclChangeSets.getAclChangeSets().stream().map(s -> s.getId()).collect(Collectors.toList()));

            logger.info("resolving acl's");
            GetAclsParam param = new GetAclsParam();
            for (AclChangeSet aclChangeSet : aclChangeSets.getAclChangeSets()) {
                param.getAclChangeSetIds().add(aclChangeSet.getId());
            }

            // max 512 aclChangeSetIds allowed
            Acls acls = alfClient.getAcls(param);


            Map<Long, Acl> aclIdMap = acls.getAcls().stream()
                    .collect(Collectors.toMap(Acl::getId, accessControlList -> accessControlList));
            logger.info("aclIds:" + aclIdMap.keySet());

            logger.info("resolving Readers");
            GetPermissionsParam grp = new GetPermissionsParam();
            grp.setAclIds(new ArrayList<>(aclIdMap.keySet()));
            ReadersACL readers = alfClient.getReader(grp);
            Map<Long, Reader> readersMap = readers.getAclsReaders().stream()
                    .collect(Collectors.toMap(Reader::getAclId, readersList -> readersList));

            logger.info("resolving AccessControlLists");
            AccessControlLists accessControlLists = alfClient.getAccessControlLists(grp);
            Map<Long, AccessControlList> accessControlListMap = accessControlLists.getAccessControlLists().stream()
                    .collect(Collectors.toMap(AccessControlList::getAclId, accessControlList -> accessControlList));

            logger.info("prepare Index Data");
            Map<Long,Map<String, List<String>>> aclPermMap = new HashMap<>();
            for (Acl acl : acls.getAcls()) {

                Reader reader = readersMap.get(acl.getId());
                if (reader.getAclId() != acl.getId()) {
                    logger.warn("reader aclid:" + reader.getAclId() + " does not match " + acl.getId());
                    continue;
                }

                List<String> alfReader = reader.getReaders();
                Collections.sort(alfReader);
                /**
                 *  alfresco permissions
                 */
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

            logger.info("updating node permissions in index");
            threadUtil.runThreaded(new ArrayList<>(aclPermMap.keySet()),aclId -> workspaceService.updateNodesWithAcl(aclId, aclPermMap.get(aclId)),true,true);
            workspaceService.refreshWorkspace();

            AclChangeSet lastAclChangeSet = aclChangeSets.getAclChangeSets().stream().max((Comparator
                    .comparingLong(AclChangeSet::getCommitTimeMs)
                    .thenComparingLong(AclChangeSet::getId)
            )).get();

            aclStateService.setState(new AclTx(lastAclChangeSet.getId(), lastAclChangeSet.getCommitTimeMs()));


            double percentage = ((double) lastAclChangeSet.getId() - 1) / (double) aclChangeSets.getMaxChangeSetId() * 100.0d;
            MetricContextHolder.getAclContext().getProgress().set((long) (percentage * PROGRESS_FACTOR));
            MetricContextHolder.getAclContext().getTimestamp().set(lastFromCommitTime);
            DecimalFormat df = new DecimalFormat("0.00");
            logger.info("finished " + df.format(percentage) + "% lastACLChangeSetId:" + lastAclChangeSet.getId() +" maxChangeSetId:" + aclChangeSets.getMaxChangeSetId());
            return true;

        }catch (IOException e) {
            logger.error(e.getMessage(), e);
            return false;
        }
    }

    /**
     * inspired by alfresco seearch services AclTracker.getSomeAclChangeSets
     * @param fromCommitTime
     * @param timeStep
     * @param maxResults
     * @param endTime
     * @return
     */
    AclChangeSets getSomeAclChangeSets(Long fromCommitTime, long timeStep, int maxResults, long endTime){
        long actualTimeStep  = timeStep;
        AclChangeSets aclChangeSets;
        // step forward in time until we find something or hit the time bound
        // max id unbounded
        Long startTime = fromCommitTime == null ? Long.valueOf(0L) : fromCommitTime;
        do
        {
            //timeStep fix: without it AclTracker could get aclChangeSets newer than transaction progress (potential missing nodes or old aclId's)
            long toTime = startTime + actualTimeStep;
            if(toTime > endTime){
                toTime = endTime;
            }

            aclChangeSets = alfClient.getAclChangeSets(null,startTime,toTime, maxResults);
            startTime += actualTimeStep;
            actualTimeStep *= 2;
            if(actualTimeStep > MAX_TIME_STEP)
            {
                actualTimeStep = MAX_TIME_STEP;
            }
        }
        while(aclChangeSets.getAclChangeSets().isEmpty() && (startTime < endTime));

        return aclChangeSets;
    }
}
