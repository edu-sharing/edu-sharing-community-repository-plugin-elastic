package org.edu_sharing.elasticsearch.tracker;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.*;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.AclTx;
import org.edu_sharing.elasticsearch.metric.MetricContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

import static org.edu_sharing.elasticsearch.metric.MetricContextHolder.MetricContext.PROGRESS_FACTOR;


@Slf4j
@Component
@RequiredArgsConstructor
public class AclTracker {

    private final AlfrescoWebscriptClient alfClient;
    private final WorkspaceService workspaceService;


    final static int maxResults = 100;

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
                log.info("got last aclTxn from index aclCommitTime:{} aclId{}", aclTx.getAclChangeSetCommitTime(), aclTx.getAclChangeSetId());
            }

            long lastACLChangeSetId = Optional.ofNullable(aclTx).map(AclTx::getAclChangeSetId).orElse(0L);
            long lastFromCommitTime = Optional.ofNullable(aclTx).map(AclTx::getAclChangeSetCommitTime).orElse(0L);

            long nextACLChangeSetId = lastACLChangeSetId + 1;

            log.info("starting lastACLChangeSetId:{} lastFromCommitTime:{} {}", nextACLChangeSetId, lastFromCommitTime, new Date(lastFromCommitTime));

            AclChangeSets aclChangeSets;
            if(lastFromCommitTime > 0){
                aclChangeSets = alfClient.getAclChangeSets(null,lastFromCommitTime + 1, AclTracker.maxResults);
            }else {
                log.warn("no last lastFromCommitTime timestamp, need to fallback to id mode, aCLChangeSetId {}", nextACLChangeSetId);
                aclChangeSets = alfClient.getAclChangeSets(nextACLChangeSetId,null, AclTracker.maxResults);
            }


            if (aclChangeSets.getAclChangeSets().isEmpty()) {
                MetricContextHolder.getAclContext().getProgress().set(100 * PROGRESS_FACTOR);
                MetricContextHolder.getAclContext().getTimestamp().set(System.currentTimeMillis());
                log.info("index is up to date:{} lastFromCommitTime:{}", nextACLChangeSetId, lastFromCommitTime);
                return false;
            }

            log.info("aclChangeSets:{}", aclChangeSets.getAclChangeSets().stream().map(AclChangeSet::getId).collect(Collectors.toList()));


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

            log.debug("aclIds:{}", grp.getAclIds().toString());
            AccessControlLists accessControlLists = alfClient.getAccessControlLists(grp);
            Map<Long, AccessControlList> accessControlListMap = accessControlLists.getAccessControlLists().stream()
                    .collect(Collectors.toMap(AccessControlList::getAclId, accessControlList -> accessControlList));

            for (Acl acl : acls.getAcls()) {

                Reader reader = readersMap.get(acl.getId());
                if (reader.getAclId() != acl.getId()) {
                    log.warn("reader aclid:{} does not match {}", reader.getAclId(), acl.getId());
                    continue;
                }

                List<String> alfReader = reader.getReaders();
                Collections.sort(alfReader);
                /*
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

            AclChangeSet lastAclChangeSet = aclChangeSets.getAclChangeSets().stream().max(Comparator.comparingLong(AclChangeSet::getCommitTimeMs)).get();

            aclStateService.setState(new AclTx(lastAclChangeSet.getId(), lastAclChangeSet.getCommitTimeMs()));


            double percentage = ((double) lastAclChangeSet.getId() - 1) / (double) aclChangeSets.getMaxChangeSetId() * 100.0d;
            MetricContextHolder.getAclContext().getProgress().set((long) (percentage * PROGRESS_FACTOR));
            MetricContextHolder.getAclContext().getTimestamp().set(lastFromCommitTime);
            DecimalFormat df = new DecimalFormat("0.00");
            log.info("finished {}% lastACLChangeSetId:{} maxChangeSetId:{}", df.format(percentage), lastAclChangeSet.getId(), aclChangeSets.getMaxChangeSetId());
            return false;

        }catch (IOException e) {
            log.error(e.getMessage(), e);
            return false;
        }
    }
}
