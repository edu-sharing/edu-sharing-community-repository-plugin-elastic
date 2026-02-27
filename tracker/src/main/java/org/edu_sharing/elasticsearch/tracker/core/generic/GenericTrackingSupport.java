package org.edu_sharing.elasticsearch.tracker.core.generic;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

public interface GenericTrackingSupport<DATA> {

    List<TimedData<DATA>> getData(OffsetDateTime fromTimeStamp, OffsetDateTime toTimeStamp,  int batchSize);

    void onHandleData(List<DATA> trackingData) throws IOException;

}



