package org.edu_sharing.elasticsearch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TrackerAvailabilityServiceTest {

    private static final long THRESHOLD_MINUTES = 10;

    private TrackerAvailabilityService underTest;

    @BeforeEach
    void setUp() {
        underTest = new TrackerAvailabilityService();
        ReflectionTestUtils.setField(underTest, "trackingTimeoutThreshold", THRESHOLD_MINUTES);
        // a fresh instance has never seen an AvailabilityChangeEvent - establish the baseline
        // that Spring Boot's actuator infrastructure publishes during startup, so getState()
        // has something to fall back to when nothing is stale.
        underTest.onApplicationEvent(new AvailabilityChangeEvent<>(this, LivenessState.CORRECT));
        underTest.onApplicationEvent(new AvailabilityChangeEvent<>(this, ReadinessState.ACCEPTING_TRAFFIC));
    }

    @Test
    void livenessStaysCorrectWhenNoTrackerHasEverTicked() {
        assertThat(underTest.getState(LivenessState.class)).isEqualTo(LivenessState.CORRECT);
    }

    @Test
    void livenessStaysCorrectRightAfterATick() {
        // Act
        underTest.tick("workspace");

        // Assert
        assertThat(underTest.getState(LivenessState.class)).isEqualTo(LivenessState.CORRECT);
    }

    @Test
    void livenessBreaksWhenASingleTrackerEntryGoesStale() {
        // Arrange: one tracker ticked once, then went silent for longer than the threshold -
        // this must be caught independently of every other (possibly still healthy) tracker.
        underTest.tick("workspace");
        ageEntry("workspace", THRESHOLD_MINUTES + 1);

        // Act & Assert
        assertThat(underTest.getState(LivenessState.class)).isEqualTo(LivenessState.BROKEN);
    }

    @Test
    void livenessStaysCorrectWhenOtherTrackersAreStillHealthy() {
        // Arrange
        underTest.tick("collection");
        underTest.tick("preview");

        // Act & Assert
        assertThat(underTest.getState(LivenessState.class)).isEqualTo(LivenessState.CORRECT);
    }

    @Test
    void clearRemovesTheEntrySoALegitimatelyFinishedTrackerCannotAgeOutLater() {
        // Arrange: without clear(), this stale entry alone would trip BROKEN below.
        underTest.tick("migration:MIGRATE_DOCUMENTS_PROGRESS_STEP");
        ageEntry("migration:MIGRATE_DOCUMENTS_PROGRESS_STEP", THRESHOLD_MINUTES + 1);

        // Act
        underTest.clear("migration:MIGRATE_DOCUMENTS_PROGRESS_STEP");

        // Assert
        assertThat(underTest.getState(LivenessState.class)).isEqualTo(LivenessState.CORRECT);
    }

    @Test
    void readinessIsForcedToAcceptingTrafficInMigrationOnlyMode() {
        // Arrange: migration-only mode never serves repository-backed traffic, so readiness
        // must not depend on whatever the rest of the application would otherwise report.
        ReflectionTestUtils.setField(underTest, "mode", "migration-only");
        underTest.onApplicationEvent(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));

        // Act & Assert
        assertThat(underTest.getState(ReadinessState.class)).isEqualTo(ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void readinessIsPassedThroughUnchangedInDefaultMode() {
        // Arrange
        underTest.onApplicationEvent(new AvailabilityChangeEvent<>(this, ReadinessState.REFUSING_TRAFFIC));

        // Act & Assert
        assertThat(underTest.getState(ReadinessState.class)).isEqualTo(ReadinessState.REFUSING_TRAFFIC);
    }

    @SuppressWarnings("unchecked")
    private void ageEntry(String trackerName, long minutesAgo) {
        Map<String, Long> entries = (Map<String, Long>) ReflectionTestUtils.getField(underTest, "lastTrackingEventByTracker");
        entries.put(trackerName, System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(minutesAgo));
    }
}
