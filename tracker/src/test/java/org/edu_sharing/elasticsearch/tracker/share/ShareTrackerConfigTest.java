package org.edu_sharing.elasticsearch.tracker.share;

import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.tracker.core.generic.GenericTrackingSupport;
import org.edu_sharing.elasticsearch.tracker.core.generic.TimedData;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.ShareInfoOplog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bulk share operations write many {@code edu_share_info_oplog} rows with the exact same
 * millisecond timestamp. To page through such ties without ever skipping or re-fetching a row,
 * the repository's oplog query now accepts an id tiebreaker (reusing the existing {@code opLogId}
 * parameter), forming a (timestamp, id) cursor. These tests verify that
 * {@code shareTrackerSupport().getData(...)} forwards that cursor correctly in both directions:
 * the incoming afterId is passed to the fetch call, and each returned entry's own id becomes its
 * {@link TimedData#sortKey()} so {@link org.edu_sharing.elasticsearch.tracker.core.generic.GenericTimebaseTracker}
 * can persist and resume the cursor.
 */
@ExtendWith(MockitoExtension.class)
class ShareTrackerConfigTest {

    @Mock
    private EduSharingService eduSharingService;

    @Mock
    private WorkspaceService workspaceService;

    private final ShareTrackerConfig config = new ShareTrackerConfig();

    private static final OffsetDateTime FROM = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime TO = OffsetDateTime.of(2026, 1, 2, 0, 0, 0, 0, ZoneOffset.UTC);

    private GenericTrackingSupport<ShareInfoOplog> support() {
        return config.shareTrackerSupport(eduSharingService, workspaceService);
    }

    private static ShareInfoOplog oplog(long id, OffsetDateTime timestamp) {
        return new ShareInfoOplog()
                .id(id)
                .shareId(id)
                .action(ShareInfoOplog.ActionEnum.CREATE)
                .timestamp(timestamp);
    }

    @Test
    void forwardsIncomingAfterIdCursorToTheFetchCall() {
        when(eduSharingService.getShareInfoOplog(eq(FROM), eq(42L), eq(TO), eq(10))).thenReturn(List.of());

        support().getData(FROM, 42L, TO, 10);

        verify(eduSharingService).getShareInfoOplog(FROM, 42L, TO, 10);
    }

    @Test
    void passesNullAfterIdThroughWhenNoCursorYet() {
        when(eduSharingService.getShareInfoOplog(eq(FROM), eq((Long) null), eq(TO), eq(10))).thenReturn(List.of());

        support().getData(FROM, null, TO, 10);

        verify(eduSharingService).getShareInfoOplog(FROM, null, TO, 10);
    }

    @Test
    void carriesEachEntrysOwnIdAsItsSortKeySoTheTrackerCanResumeExactlyThere() {
        OffsetDateTime tie = FROM.plusMinutes(1);
        List<ShareInfoOplog> fetched = List.of(oplog(101, tie), oplog(102, tie), oplog(103, tie));
        when(eduSharingService.getShareInfoOplog(eq(FROM), eq((Long) null), eq(TO), eq(3))).thenReturn(fetched);

        List<TimedData<ShareInfoOplog>> result = support().getData(FROM, null, TO, 3);

        assertThat(result).extracting(TimedData::sortKey).containsExactly(101L, 102L, 103L);
        assertThat(result).extracting(d -> d.data().getShareId()).containsExactly(101L, 102L, 103L);
    }
}
