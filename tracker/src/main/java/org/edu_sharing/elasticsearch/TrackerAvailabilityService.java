package org.edu_sharing.elasticsearch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityState;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class TrackerAvailabilityService extends ApplicationAvailabilityBean implements TrackerAvailabilityTickService {
    // curl localhost:8081/actuator/health/liveness
    // per tracker name, so one tracker stuck in a single, never-returning run is caught even
    // while every other tracker keeps ticking (a shared global timestamp would mask that case).
    // Absence of an entry is never treated as broken, only an entry that exists and has gone
    // stale is - a tracker/migration step that legitimately finished must call clear() so its
    // last timestamp doesn't just sit there and inevitably age past the threshold on its own.
    private final Map<String, Long> lastTrackingEventByTracker = new ConcurrentHashMap<>();
    @Value("${management.endpoint.health.trackingTimeoutThreshold}")
    private long trackingTimeoutThreshold;
    @Value("${mode:#{null}}")
    private String mode;
    @Override
    public void tick(String trackerName) {
        lastTrackingEventByTracker.put(trackerName, System.currentTimeMillis());
    }

    @Override
    public void clear(String trackerName) {
        lastTrackingEventByTracker.remove(trackerName);
    }

    @Override
    public <S extends AvailabilityState> S getState(Class<S> stateType) {
        S state = super.getState(stateType);
        if (state instanceof LivenessState) {
            long thresholdMs = trackingTimeoutThreshold * 1000 * 60;
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> entry : lastTrackingEventByTracker.entrySet()) {
                long diff = now - entry.getValue();
                if (diff > thresholdMs) {
                    log.warn("Liveness probe: Fail cause of last tracking event delay for tracker '{}' (last event: {}, diff: {})", entry.getKey(), entry.getValue(), diff);
                    return (S) LivenessState.BROKEN;
                }
            }
        }
        if(ReadinessState.class.equals(stateType) && "migration-only".equals(mode)) {
            // migration is ready as long as timeout isn't reached
            return (S) ReadinessState.ACCEPTING_TRAFFIC;
        }
        return state;
    }
}