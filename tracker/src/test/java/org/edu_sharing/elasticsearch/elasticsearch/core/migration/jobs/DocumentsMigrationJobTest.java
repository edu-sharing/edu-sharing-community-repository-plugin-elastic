package org.edu_sharing.elasticsearch.elasticsearch.core.migration.jobs;

import org.edu_sharing.elasticsearch.TrackerAvailabilityTickService;
import org.edu_sharing.elasticsearch.elasticsearch.core.AdminService;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexServiceFactory;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationContext;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationException;
import org.edu_sharing.elasticsearch.tracker.core.TrackerConfig;
import org.edu_sharing.elasticsearch.tracker.core.TrackerExecutorFactory;
import org.edu_sharing.elasticsearch.tracker.core.TrackerRegistry;
import org.edu_sharing.elasticsearch.tracker.core.TrackingExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentsMigrationJobTest {

    @Mock
    private AdminService adminService;
    @Mock
    private TrackerRegistry trackerRegistry;
    @Mock
    private StatusIndexServiceFactory statusIndexServiceFactory;
    @Mock
    private TrackerExecutorFactory trackerExecutorFactory;
    @Mock
    private TrackerAvailabilityTickService tickService;

    private DocumentsMigrationJob job;
    private String jobTickName;

    @BeforeEach
    void setUp() {
        job = new DocumentsMigrationJob(
                adminService,
                "migration_test_tracker",
                trackerRegistry,
                Collections.emptyList(),
                statusIndexServiceFactory,
                trackerExecutorFactory,
                tickService);
        jobTickName = MigrationJob.tickName(job);
    }

    @Test
    void onEnterStateTicksLivenessBeforeAnyIndexWorkSoTheSetupPhaseIsCovered() throws IOException {
        // Arrange: the ES-only setup work in onEnterState() used to run with no liveness
        // coverage at all - simulate it failing right away to prove the tick already
        // happened before that point, not only on the happy path.
        when(adminService.createIndex(any(IndexConfiguration.class))).thenThrow(new IOException("boom"));

        // Act
        assertThatThrownBy(() -> job.onEnterState(mock(MigrationContext.class)))
                .isInstanceOf(MigrationException.class);

        // Assert
        verify(tickService).tick(jobTickName);
    }

    @Test
    void onProgressStateClearsJobLevelTickImmediatelyWithoutWaitingForItsTrackersToFinish() throws Exception {
        // Arrange: one tracker whose track() call blocks until we release it, simulating a
        // long-running (but healthy) migration tracker.
        TrackerConfig<?, ?> trackerConfig = mock(TrackerConfig.class);
        when(trackerConfig.getName()).thenReturn("workspace");

        TrackingExecutor<?> trackingExecutor = mock(TrackingExecutor.class);
        CountDownLatch trackStarted = new CountDownLatch(1);
        CountDownLatch trackMayFinish = new CountDownLatch(1);
        doAnswer(invocation -> {
            trackStarted.countDown();
            trackMayFinish.await(5, TimeUnit.SECONDS);
            return null;
        }).when(trackingExecutor).track();

        Map<TrackerConfig<?, ?>, TrackingExecutor<?>> trackingExecutors = new LinkedHashMap<>();
        trackingExecutors.put(trackerConfig, trackingExecutor);
        ReflectionTestUtils.setField(job, "trackingExecutors", trackingExecutors);

        Throwable[] failureFromBackgroundThread = new Throwable[1];
        Thread progressThread = new Thread(() -> {
            try {
                job.onProgressState(mock(MigrationContext.class));
            } catch (Throwable t) {
                failureFromBackgroundThread[0] = t;
            }
        });

        // Act
        progressThread.start();
        assertThat(trackStarted.await(5, TimeUnit.SECONDS)).as("tracker's track() should have started").isTrue();

        // Assert: the job-level tick is handed off to the (still running) per-tracker tick
        // right after submission - it must not sit frozen until the tracker eventually
        // finishes, or it would age past the liveness threshold during a long-running but
        // healthy migration.
        verify(tickService, timeout(2000)).clear(jobTickName);
        verify(tickService, never()).clear("workspace");

        // release the tracker and let onProgressState() conclude normally
        trackMayFinish.countDown();
        progressThread.join(5000);

        assertThat(progressThread.isAlive()).isFalse();
        assertThat(failureFromBackgroundThread[0]).isNull();
        verify(tickService, timeout(2000)).clear("workspace");
    }
}
