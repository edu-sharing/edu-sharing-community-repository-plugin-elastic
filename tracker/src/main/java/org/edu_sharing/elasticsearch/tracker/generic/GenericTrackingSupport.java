package org.edu_sharing.elasticsearch.tracker.generic;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

public interface GenericTrackingSupport<DATA, STATE> {
    String getName();

    List<DATA> getData(OffsetDateTime lastTimestamp, int batchSize);

    Long getTimestamp(DATA data);

    STATE stateApplier(STATE state, long lastTimestamp);

    Long lastTimestampSupplier(STATE state);

    void onHandleData(List<DATA> trackingData) throws IOException;

}
