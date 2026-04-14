package org.edu_sharing.elasticsearch.tracker.debug;

import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.tracker.core.AbstractAlfTransactionTracker;
import org.edu_sharing.elasticsearch.tracker.core.TrackingContext;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class DebugTracker extends AbstractAlfTransactionTracker<AlfTransactionTrackerProperties> {


    public DebugTracker(AlfTransactionTrackerProperties debugTrackerProps) {
        super(debugTrackerProps);
    }

    @Override
    public void trackNodes(List<Node> nodes) {
        log.info("Generic MetadataTracker called!");
    }

    @Override
    public State track(TrackingContext<Tx> trackingContext) {
        return super.track(trackingContext);
    }
}
