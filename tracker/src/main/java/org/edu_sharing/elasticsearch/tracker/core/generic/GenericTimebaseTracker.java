package org.edu_sharing.elasticsearch.tracker.core.generic;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.metric.MetricContext;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.core.AbstractTracker;
import org.edu_sharing.elasticsearch.tracker.core.TrackingContext;
import org.edu_sharing.elasticsearch.tracker.strategy.CommitTimeStatus;
import org.jetbrains.annotations.NotNull;

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

    /**
     * A time windowed data source hands out the next batch but never says how many entries are still
     * behind it, so there is no total this tracker could report a percentage of. Anything it could
     * derive from the time axis instead would answer a different question than the same gauge does
     * for the transaction based trackers ("position in the whole source"), so it stays with
     * {@code trackerDelay}, which it can state exactly.
     */
    @Override
    public boolean reportsProgress() {
        return false;
    }

    @Override
    public State track(TrackingContext<TimeBasedStatus> context) {
        try {
            log.info("Starting tracking {}", getName());

            TimeBasedStatus trackerStatus = context.statusIndexService().getState();

            OffsetDateTime lastTimestampDate = OffsetDateTime.ofInstant(
                    Instant.ofEpochMilli(Optional.ofNullable(trackerStatus).map(CommitTimeStatus::getCommitTime).orElse(0L)),
                    ZoneOffset.UTC);
            Long lastId = Optional.ofNullable(trackerStatus).map(TimeBasedStatus::getLastId).orElse(null);

            Long limit = context.strategy().getLimit();
            OffsetDateTime toTimeStamp = limit != null
                    ? OffsetDateTime.ofInstant(Instant.ofEpochMilli(limit), ZoneOffset.UTC)
                    : null;

            log.info("{} starting from: {}", getName(), dateFormat.format(lastTimestampDate));

            int i = 0;
            do {
                List<TimedData<DATA>> trackingData = trackingSupport.getData(lastTimestampDate, lastId, toTimeStamp, props.getBatchSize());
                if (trackingData.isEmpty()) {
                    log.info("{} no new data found", getName());
                    trackCaughtUp(context.metricContext(), limit);
                    return State.FINISHED;
                }

                TimedData<DATA> lastData = trackingData.get(trackingData.size() - 1);
                lastTimestampDate = Instant.ofEpochMilli(lastData.timestamp()).atOffset(ZoneOffset.UTC);
                lastId = lastData.sortKey();

                trackingSupport.onHandleData(trackingData.stream().map(TimedData::data).toList());
                log.info("{} handled {} entries", getName(), trackingData.size());
                context.statusIndexService().setState(new TimeBasedStatus(lastTimestampDate.toInstant().toEpochMilli(), lastId));

                context.metricContext().getTimestamp().set(lastData.timestamp());
                log.info("{} finished up to {} ({} hours behind)",
                        getName(),
                        dateFormat.format(lastTimestampDate),
                        Tools.df.format((System.currentTimeMillis() - lastData.timestamp()) / 1000.0 / 60 / 60));
            } while (i++ < props.getMaxIterations());
            log.info("finished {} until: {}", getName(), dateFormat.format(lastTimestampDate));
        } catch (Exception e) {
            log.error("Error tracking {}: {}", getName(), e.getMessage(), e);
            return State.EXCEPTION;
        }
        return State.IN_PROGRESS;
    }

    /**
     * Everything the tracker is currently allowed to see is indexed. Without a strategy limit that
     * means "up to date with the data source", so the delay is measured against now. With a limit
     * the tracker is only up to date with its dependency, and reporting now would hide how far that
     * dependency itself is lagging - so the limit stays the reference point.
     */
    private void trackCaughtUp(MetricContext metricContext, Long limit) {
        metricContext.getTimestamp().set(limit != null ? limit : System.currentTimeMillis());
    }
}
