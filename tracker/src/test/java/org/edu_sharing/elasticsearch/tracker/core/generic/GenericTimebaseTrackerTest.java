package org.edu_sharing.elasticsearch.tracker.core.generic;

import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceInterface;
import org.edu_sharing.elasticsearch.metric.MetricContext;
import org.edu_sharing.elasticsearch.tracker.core.Tracker;
import org.edu_sharing.elasticsearch.tracker.core.TrackingContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A DB-backed oplog can contain many rows with the exact same millisecond timestamp (e.g. a bulk
 * share operation). {@link GenericTimebaseTracker} must page through such ties via a (timestamp,
 * id) cursor without ever skipping a row (data loss) or re-delivering one (redundant work), even
 * when a tie group straddles a batch boundary. This test drives the tracker against an in-memory
 * fake that mimics the composite-cursor SQL query (WHERE (timestamp, id) > (?, ?)).
 *
 * <p>Protection against concurrent, out-of-order-committing transactions (a slower transaction
 * committing a row with an earlier (timestamp, id) after a faster, later-started one was already
 * picked up) is handled DB-side in {@code ShareInfoOpLogMapper} via a {@code pg_stat_activity}
 * based safe watermark, not here - see that mapper's javadoc.
 */
class GenericTimebaseTrackerTest {

    private record Row(long id, long timestamp) {
    }

    /**
     * Mimics "SELECT ... WHERE (timestamp, id) > (fromTimestamp, afterId) ORDER BY timestamp, id
     * FETCH NEXT batchSize ROWS ONLY".
     */
    private static class FakeSupport implements GenericTrackingSupport<Long> {
        private final List<Row> rows;
        private final List<Long> delivered = new ArrayList<>();

        FakeSupport(List<Row> rows) {
            this.rows = rows;
        }

        @Override
        public List<TimedData<Long>> getData(OffsetDateTime fromTimeStamp, Long afterId, OffsetDateTime toTimeStamp, int batchSize) {
            long fromMillis = fromTimeStamp.toInstant().toEpochMilli();
            long afterIdOrMin = afterId != null ? afterId : Long.MIN_VALUE;
            Long toMillis = toTimeStamp != null ? toTimeStamp.toInstant().toEpochMilli() : null;
            return rows.stream()
                    .filter(r -> r.timestamp() > fromMillis || (r.timestamp() == fromMillis && r.id() > afterIdOrMin))
                    .filter(r -> toMillis == null || r.timestamp() <= toMillis)
                    .sorted(Comparator.comparingLong(Row::timestamp).thenComparingLong(Row::id))
                    .limit(batchSize)
                    .map(r -> new TimedData<>(r.id(), r.timestamp(), r.id()))
                    .toList();
        }

        @Override
        public void onHandleData(List<Long> trackingData) throws IOException {
            delivered.addAll(trackingData);
        }
    }

    private static class InMemoryStatus implements StatusIndexServiceInterface<TimeBasedStatus> {
        private TimeBasedStatus state;

        @Override
        public Class<TimeBasedStatus> getStateClass() {
            return TimeBasedStatus.class;
        }

        @Override
        public TimeBasedStatus getState() {
            return state;
        }

        @Override
        public void setState(TimeBasedStatus state) {
            this.state = state;
        }

        @Override
        public void resetState() {
            state = null;
        }
    }

    private static List<Long> runToCompletion(FakeSupport support) {
        GenericTimebaseTrackerProperties props = new GenericTimebaseTrackerProperties();
        props.setBatchSize(3);
        props.setMaxIterations(50);
        GenericTimebaseTracker<GenericTimebaseTrackerProperties, Long> tracker = new GenericTimebaseTracker<>(props, support);
        tracker.setName("test");

        InMemoryStatus statusIndexService = new InMemoryStatus();
        TrackingContext<TimeBasedStatus> context = new TrackingContext<>(
                "test", () -> null, statusIndexService, MetricContext.builder().build());

        Tracker.State state;
        int safety = 0;
        do {
            state = tracker.track(context);
            assertThat(safety++).isLessThan(1000);
        } while (state == Tracker.State.IN_PROGRESS);
        assertThat(state).isEqualTo(Tracker.State.FINISHED);
        return support.delivered;
    }

    @Test
    void deliversEveryRowExactlyOnceAcrossBatchBoundaries() {
        // batchSize is 3; timestamp 100 alone has 5 rows - a tie group larger than one batch
        List<Row> rows = List.of(
                new Row(1, 100), new Row(2, 100), new Row(3, 100), new Row(4, 100), new Row(5, 100),
                new Row(6, 200), new Row(7, 200),
                new Row(8, 300)
        );
        FakeSupport support = new FakeSupport(rows);

        List<Long> delivered = runToCompletion(support);

        assertThat(delivered).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }

    @Test
    void resumesFromPersistedCompositeCursorWithoutReDeliveringTheBoundaryRow() {
        List<Row> rows = new ArrayList<>(List.of(
                new Row(1, 100), new Row(2, 100), new Row(3, 200)
        ));
        FakeSupport firstRunSupport = new FakeSupport(rows);
        GenericTimebaseTrackerProperties props = new GenericTimebaseTrackerProperties();
        props.setBatchSize(2);
        props.setMaxIterations(0); // exactly one fetch per track() call (the do-while runs once, then i++ < 0 is false)
        GenericTimebaseTracker<GenericTimebaseTrackerProperties, Long> tracker = new GenericTimebaseTracker<>(props, firstRunSupport);
        tracker.setName("test");
        InMemoryStatus statusIndexService = new InMemoryStatus();
        TrackingContext<TimeBasedStatus> context = new TrackingContext<>(
                "test", () -> null, statusIndexService, MetricContext.builder().build());

        // first poll: only rows 1 and 2 (both at timestamp 100) fit the batch of 2
        tracker.track(context);
        assertThat(firstRunSupport.delivered).containsExactly(1L, 2L);
        assertThat(statusIndexService.getState().getCommitTime()).isEqualTo(100L);
        assertThat(statusIndexService.getState().getLastId()).isEqualTo(2L);

        // a new tracker instance (e.g. after a restart) resumes from the persisted cursor
        GenericTimebaseTracker<GenericTimebaseTrackerProperties, Long> resumedTracker = new GenericTimebaseTracker<>(props, firstRunSupport);
        resumedTracker.setName("test");
        resumedTracker.track(context);

        // row 3 must be delivered, but rows 1/2 (already at/behind the cursor) must not repeat
        assertThat(firstRunSupport.delivered).containsExactly(1L, 2L, 3L);
    }
}
