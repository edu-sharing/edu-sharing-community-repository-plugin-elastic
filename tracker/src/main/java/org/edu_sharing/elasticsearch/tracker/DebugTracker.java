package org.edu_sharing.elasticsearch.tracker;

import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Node;

import java.io.IOException;
import java.util.List;

@Slf4j
public class DebugTracker extends TransactionTrackerBase {

    public DebugTracker(){
        super();
    }

    @Override
    public void trackNodes(List<Node> nodes) throws IOException {
        log.info("Generic MetadataTracker called!");
    }

    @Override
    public State track() {
        return super.track();
    }
}
