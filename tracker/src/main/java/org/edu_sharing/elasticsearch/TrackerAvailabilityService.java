package org.edu_sharing.elasticsearch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.ApplicationAvailabilityBean;
import org.springframework.boot.availability.AvailabilityState;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class TrackerAvailabilityService extends ApplicationAvailabilityBean implements TrackerAvailabilityTickService {
    // curl localhost:8081/actuator/health/liveness
    private long lastTrackingEvent = System.currentTimeMillis();
    @Value("${management.endpoint.health.trackingTimeoutThreshold}")
    private long trackingTimeoutThreshold;
    @Value("${mode:#{null}}")
    private String mode;
    @Override
    public void tick() {
        lastTrackingEvent = System.currentTimeMillis();
    }

    @Override
    public <S extends AvailabilityState> S getState(Class<S> stateType) {
        S state = super.getState(stateType);
        if(state instanceof LivenessState && (System.currentTimeMillis() - lastTrackingEvent) > trackingTimeoutThreshold * 1000 * 60) {
            log.warn("Liveness probe: Fail cause of last tracking event delay (last event: {}, diff: {}", lastTrackingEvent, System.currentTimeMillis() - lastTrackingEvent);
            return (S) LivenessState.BROKEN;
        }
        if(ReadinessState.class.equals(stateType) && "migration-only".equals(mode)) {
            // migration is ready as long as timeout isn't reached
            return (S) ReadinessState.ACCEPTING_TRAFFIC;
        }
        return state;
    }
}