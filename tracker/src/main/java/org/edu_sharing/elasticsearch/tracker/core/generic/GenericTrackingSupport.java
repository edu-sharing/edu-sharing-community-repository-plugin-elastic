package org.edu_sharing.elasticsearch.tracker.core.generic;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

public interface GenericTrackingSupport<DATA> {

    /**
     * Must return at most {@code batchSize} entries, ordered by (timestamp, sortKey) ascending -
     * {@link GenericTimebaseTracker} takes the last one as its new cursor.
     *
     * @param afterId tiebreaker cursor for entries sharing the exact same fromTimeStamp (see
     *                {@link TimedData#sortKey()}); null if the previous batch's last entry had
     *                no sort key, or no entry has been processed yet. Data sources without a
     *                stable id can ignore it.
     */
    List<TimedData<DATA>> getData(OffsetDateTime fromTimeStamp, Long afterId, OffsetDateTime toTimeStamp, int batchSize);

    void onHandleData(List<DATA> trackingData) throws IOException;

}



