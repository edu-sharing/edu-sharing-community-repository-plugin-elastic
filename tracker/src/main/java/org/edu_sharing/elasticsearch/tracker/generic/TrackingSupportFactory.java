package org.edu_sharing.elasticsearch.tracker.generic;

import java.util.List;

public interface TrackingSupportFactory<DATA, STATE> {
    List<GenericTrackingSupport<DATA, STATE>> getTrackingSupport();
}
