package org.edu_sharing.elasticsearch.tracker.core.generic;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.tracker.core.AbstractTracker;
import org.edu_sharing.elasticsearch.tracker.core.TrackingContext;
import org.edu_sharing.elasticsearch.tracker.strategy.CommitTimeStatus;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
public class GenericTimebaseTracker<PROPS extends GenericTimebaseTrackerProperties, DATA> extends AbstractTracker<PROPS, TimeBasedStatus> {


    protected static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);


    protected final GenericTrackingSupport<DATA> trackingSupport;

    public GenericTimebaseTracker(PROPS props, @NotNull @NonNull GenericTrackingSupport<DATA> trackingSupport) {
        super(props);
        this.trackingSupport = trackingSupport;
    }


    @Override
    public Class<TimeBasedStatus> getStatusClass() {
        return TimeBasedStatus.class;
    }

    @Override
    public State track(TrackingContext<TimeBasedStatus> context) {
        try {
            log.info("Starting tracking {}", getName());

            TimeBasedStatus trackerStatus = context.statusIndexService().getState();

            OffsetDateTime lastTimestampDate = OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(Optional.ofNullable(trackerStatus).map(CommitTimeStatus::getCommitTime).orElse(0L)),
                    ZoneOffset.UTC);

            OffsetDateTime toTimeStamp = context.strategy().getLimit() != null
                    ? OffsetDateTime.ofInstant(Instant.ofEpochMilli(context.strategy().getLimit()), ZoneOffset.UTC)
                    : null;

            log.info("{} starting from: {}", getName(), dateFormat.format(lastTimestampDate));

            int i = 0;
            do {
                List<TimedData<DATA>> trackingData = trackingSupport.getData(lastTimestampDate, toTimeStamp, props.getBatchSize());
                if (trackingData.isEmpty()) {
                    log.info("{} no new data found", getName());
                    return State.FINISHED;
                }

                TimedData<DATA> lastData = trackingData.get(trackingData.size() - 1);
                lastTimestampDate = Instant.ofEpochMilli(lastData.timestamp()).atOffset(ZoneOffset.UTC);

                trackingSupport.onHandleData(trackingData.stream().map(TimedData::data).toList());
                log.info("{} handled {} entries", getName(), trackingData.size());
                context.statusIndexService().setState(new TimeBasedStatus(lastTimestampDate.toInstant().toEpochMilli()));
            } while (i++ < props.getMaxIterations());
            log.info("finished {} until: {}", getName(), dateFormat.format(lastTimestampDate));
        } catch (IOException e) {
            log.error("Error tracking {}: {}", getName(), e.getMessage(), e);
            return State.EXCEPTION;
        }
        return State.IN_PROGRESS;
    }
}


