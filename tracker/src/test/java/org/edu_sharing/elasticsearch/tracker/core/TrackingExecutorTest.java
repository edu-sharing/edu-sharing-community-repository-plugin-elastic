package org.edu_sharing.elasticsearch.tracker.core;

import org.edu_sharing.elasticsearch.TrackerAvailabilityTickService;
import org.edu_sharing.elasticsearch.elasticsearch.core.ApplicationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrackingExecutorTest {

    private static final String TRACKER_NAME = "workspace";

    @Mock
    private Tracker<Object> tracker;
    @Mock
    private ApplicationState applicationState;
    @Mock
    private TrackerAvailabilityTickService tickService;

    private TrackingExecutor<Object> underTest;

    @BeforeEach
    void setUp() {
        TrackingContext<Object> context = new TrackingContext<>(TRACKER_NAME, null, null, null);
        underTest = new TrackingExecutor<>(tracker, context, applicationState, tickService);
    }

    @Test
    void ticksBeforeTheReadinessGateSoAStuckSchedulerIsStillDetectable() {
        // Arrange: canTrack() == false is the normal "not ready yet" case (e.g. repository not
        // reachable) - the tick before the gate must still fire, otherwise a tracker parked here
        // forever would never trip the liveness probe.
        when(applicationState.canTrack()).thenReturn(false);

        // Act
        underTest.track();

        // Assert
        verify(tickService).tick(TRACKER_NAME);
        verify(tracker, never()).track(any());
    }

    @Test
    void ticksOnceUpFrontPlusOncePerRecursiveIterationAndNeverClears() {
        // Arrange: a backlog that needs three recursive catch-up iterations before finishing.
        when(applicationState.canTrack()).thenReturn(true);
        when(tracker.track(any()))
                .thenReturn(Tracker.State.IN_PROGRESS)
                .thenReturn(Tracker.State.IN_PROGRESS)
                .thenReturn(Tracker.State.FINISHED);

        // Act
        underTest.track();

        // Assert: one tick before the gate + one per do-while iteration (3 iterations here) -
        // a long but healthy catch-up run must never be misreported as stuck.
        verify(tickService, times(4)).tick(TRACKER_NAME);
        verify(tracker, times(3)).track(any());
        // TrackingExecutor deliberately never clears: in default mode it reschedules forever
        // (see TrackerScheduler), and in migration-only mode the one-shot caller
        // (DocumentsMigrationJob) owns clearing once it knows no further run will happen.
        verify(tickService, never()).clear(anyString());
    }

    @Test
    void aTrackerThatThrowsStillTickedBeforeFailingAndDoesNotPropagate() {
        // Arrange
        when(applicationState.canTrack()).thenReturn(true);
        when(tracker.track(any())).thenThrow(new RuntimeException("boom"));

        // Act
        underTest.track();

        // Assert: the failure is logged and swallowed (pre-existing behavior), but the tick
        // that proves this run was alive right up to the failure must still have happened.
        verify(tickService, times(2)).tick(TRACKER_NAME);
        verify(tickService, never()).clear(anyString());
    }
}
