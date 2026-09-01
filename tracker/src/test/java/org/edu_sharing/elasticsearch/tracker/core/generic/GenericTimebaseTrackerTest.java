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

    /**
     * A time based tracker feeds only {@code trackerDelay}: while it is working through a backlog the
     * gauge carries the timestamp of the last indexed entry, so the delay is that entry's real age.
     */
    @Test
    void reportsTheAgeOfTheLastIndexedEntryWhileWorkingThroughABacklog() {
        List<Row> rows = new ArrayList<>(List.of(
                new Row(1, 1000), new Row(2, 2000), new Row(3, 3000)
        ));
        FakeSupport support = new FakeSupport(rows);
        GenericTimebaseTrackerProperties props = new GenericTimebaseTrackerProperties();
        props.setBatchSize(1);
        props.setMaxIterations(0); // one fetch per track() call
        GenericTimebaseTracker<GenericTimebaseTrackerProperties, Long> tracker = new GenericTimebaseTracker<>(props, support);
        tracker.setName("test");

        MetricContext metricContext = MetricContext.builder().name("test").build();
        TrackingContext<TimeBasedStatus> context = new TrackingContext<>(
                "test", () -> 5000L, new InMemoryStatus(), metricContext);

        tracker.track(context);
        assertThat(metricContext.getTimestamp()).hasValue(1000L);

        tracker.track(context);
        assertThat(metricContext.getTimestamp()).hasValue(2000L);

        tracker.track(context);
        assertThat(metricContext.getTimestamp()).hasValue(3000L);

        // caught up: the delay falls back to the frontier the tracker was allowed to reach, which
        // under a dependent tracker strategy is that dependency's commit time - not now, so its own
        // lag stays visible.
        assertThat(tracker.track(context)).isEqualTo(Tracker.State.FINISHED);
        assertThat(metricContext.getTimestamp()).hasValue(5000L);
    }

    /**
     * Without a dependent tracker limiting it, being out of data means being up to date with the
     * source - the delay has to drop to ~0 instead of freezing at the last indexed entry.
     */
    @Test
    void reportsZeroDelayWhenCaughtUpWithoutStrategyLimit() {
        FakeSupport support = new FakeSupport(List.of(new Row(1, 1000)));
        GenericTimebaseTrackerProperties props = new GenericTimebaseTrackerProperties();
        props.setBatchSize(10);
        props.setMaxIterations(50);
        GenericTimebaseTracker<GenericTimebaseTrackerProperties, Long> tracker = new GenericTimebaseTracker<>(props, support);
        tracker.setName("test");

        MetricContext metricContext = MetricContext.builder().name("test").build();
        TrackingContext<TimeBasedStatus> context = new TrackingContext<>(
                "test", () -> null, new InMemoryStatus(), metricContext);

        long before = System.currentTimeMillis();
        while (tracker.track(context) == Tracker.State.IN_PROGRESS) {
            // drain
        }

        assertThat(metricContext.getTimestamp().get()).isBetween(before, System.currentTimeMillis());
    }

    /**
     * The progress gauge must not be registered for this tracker - an unfed gauge would sit at 0 and
     * keep the shared "low tracking progress" alert firing forever.
     */
    @Test
    void reportsNoProgress() {
        GenericTimebaseTracker<GenericTimebaseTrackerProperties, Long> tracker =
                new GenericTimebaseTracker<>(new GenericTimebaseTrackerProperties(), new FakeSupport(List.of()));

        assertThat(tracker.reportsProgress()).isFalse();
    }
}
