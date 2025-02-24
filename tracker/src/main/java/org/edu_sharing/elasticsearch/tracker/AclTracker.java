package org.edu_sharing.elasticsearch.tracker;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.alfresco.client.*;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.AclTx;
import org.edu_sharing.elasticsearch.metric.MetricContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

import static org.edu_sharing.elasticsearch.metric.MetricContextHolder.MetricContext.PROGRESS_FACTOR;


@Component
@RequiredArgsConstructor
public class AclTracker {

    private final AlfrescoWebscriptClient alfClient;
    private final WorkspaceService workspaceService;

    @Value("${allowed.types}")
    String allowedTypes;

    final static int maxResults = 100;

    @Value("${tracker.timestep:36000000}")
    int nextTimeStep;

    Logger logger = LoggerFactory.getLogger(AclTracker.class);
    private final StatusIndexService<AclTx> aclStateService;


//    @PostConstruct
//    public void init() throws IOException {
//        ACLChangeSet aclChangeSet;
//        try {
//            aclChangeSet = aclStateService.getState();
//            if (aclChangeSet != null) {
//                lastFromCommitTime = aclChangeSet.getAclChangeSetCommitTime();
//                lastACLChangeSetId = aclChangeSet.getAclChangeSetId();
//                logger.info("got last aclChangeSet from index aclCommitTime:" + aclChangeSet.getAclChangeSetCommitTime() + " aclId" + aclChangeSet.getAclChangeSetId());
//            }
//        } catch (IOException e) {
//            logger.error("problems reaching elastic search server");
//            throw e;
//        }
//    }

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
                aclChangeSets = alfClient.getAclChangeSets(null,lastFromCommitTime + 1, AclTracker.maxResults);
            }else {
                logger.warn("no last lastFromCommitTime timestamp, need to fallback to id mode, aCLChangeSetId {}", nextACLChangeSetId);
                aclChangeSets = alfClient.getAclChangeSets(nextACLChangeSetId,null, AclTracker.maxResults);
            }


            if (aclChangeSets.getAclChangeSets().isEmpty()) {
                logger.info("index is up to date:" + nextACLChangeSetId + " lastFromCommitTime:" + lastFromCommitTime);
                    MetricContextHolder.getAclContext().getProgress().set(100 * PROGRESS_FACTOR);
                    MetricContextHolder.getAclContext().getTimestamp().set(System.currentTimeMillis());
                } else {
                    //should not happen
                    logger.info("did not found new aclchangesets in last aclchangeset block from:" + (nextACLChangeSetId ) + " MaxChangeSetId:" + aclChangeSets.getMaxChangeSetId());
                }
                return false;
            }

            logger.info("aclChangeSets:" + aclChangeSets.getAclChangeSets().stream().map(s -> s.getId()).collect(Collectors.toList()));


            GetAclsParam param = new GetAclsParam();
            for (AclChangeSet aclChangeSet : aclChangeSets.getAclChangeSets()) {
                param.getAclChangeSetIds().add(aclChangeSet.getId());
            }


            Acls acls = alfClient.getAcls(param);

            GetPermissionsParam grp = new GetPermissionsParam();
            Map<Long, Acl> aclIdMap = acls.getAcls().stream()
                    .collect(Collectors.toMap(Acl::getId, accessControlList -> accessControlList));

            grp.setAclIds(new ArrayList<>(aclIdMap.keySet()));
            ReadersACL readers = alfClient.getReader(grp);
            Map<Long, Reader> readersMap = readers.getAclsReaders().stream()
                    .collect(Collectors.toMap(Reader::getAclId, readersList -> readersList));

            logger.debug("aclIds:" + grp.getAclIds().toString());
            AccessControlLists accessControlLists = alfClient.getAccessControlLists(grp);
            Map<Long, AccessControlList> accessControlListMap = accessControlLists.getAccessControlLists().stream()
                    .collect(Collectors.toMap(AccessControlList::getAclId, accessControlList -> accessControlList));

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
                workspaceService.updateNodesWithAcl(acl.getId(), permissionsAlf);
            }

            AclChangeSet lastAclChangeSet = aclChangeSets.getAclChangeSets().stream().max((a, b) -> Long.compare(
                    a.getCommitTimeMs(), b.getCommitTimeMs()
            )).get();

            aclStateService.setState(new AclTx(lastAclChangeSet.getId(), lastAclChangeSet.getCommitTimeMs()));


            double percentage = ((double) lastAclChangeSet.getId() - 1) / (double) aclChangeSets.getMaxChangeSetId() * 100.0d;
            MetricContextHolder.getAclContext().getProgress().set((long) (percentage * PROGRESS_FACTOR));
            MetricContextHolder.getAclContext().getTimestamp().set(lastFromCommitTime);
            DecimalFormat df = new DecimalFormat("0.00");
            logger.info("finished " + df.format(percentage) + "% lastACLChangeSetId:" + lastAclChangeSet.getId() +" maxChangeSetId:" + aclChangeSets.getMaxChangeSetId());
            return false;

        }catch (IOException e) {
            logger.error(e.getMessage(), e);
            return false;
        }
    }
}
