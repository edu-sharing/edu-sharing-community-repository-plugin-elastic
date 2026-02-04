package org.edu_sharing.elasticsearch.tracker.generic;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.edu_sharing.elasticsearch.tracker.Tracker;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@RequiredArgsConstructor
public class GenericTimebaseTracker<DATA, STATE> implements Tracker {

    protected final StatusIndexService<STATE> transactionStatusIndexService;
    protected final StatusIndexService<Tx> txStatusIndexService;

    protected Set<GenericTrackingSupport<DATA, STATE>> trackingSupports = new LinkedHashSet<>();

    protected static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);


    /**
     * Defines the size of data batches to be retrieved or processed during tracking operations.
     * This variable sets the maximum number of items to handle in a single batch,
     * enabling efficient processing by dividing large datasets into smaller, more manageable chunks.
     * Used primarily in data-fetching and handling logic for tracking supports.
     * The default value is set to 1000.
     */
    @Getter
    @Setter
    protected int batchSize = 1000;

    /**
     * Represents the maximum number of iterations to perform during the tracking process.
     * This value is used to limit the number of iterations in repetitive operations
     * such as fetching and processing data to prevent infinite loops or excessive execution.
     * The default value is 10 iterations.
     */
    @Getter
    @Setter
    protected int maxIterations = 10;


    /**
     * Adds one or more tracking support instances to the tracker. Each tracking support provides functionality
     * for handling specific types of data and state management within the tracking process.
     *
     * @param trackingSupport an array of {@link GenericTrackingSupport} objects that define the tracking behavior
     *                        and data handling logic for specific types of data and states.
     */
    @SafeVarargs
    public final void addTrackingSupport(GenericTrackingSupport<DATA, STATE>... trackingSupport) {
        addTrackingSupport(Arrays.asList(trackingSupport));
    }

    /**
     * Adds a list of tracking support instances to the tracker. Each tracking support instance
     * provides functionality for handling specific types of data and state management within
     * the tracking process.
     *
     * @param trackingSupports a list of {@link GenericTrackingSupport} objects that define the
     *                         tracking behavior and data handling logic for specific types
     *                         of data and states.
     */
    public final void addTrackingSupport(List<GenericTrackingSupport<DATA, STATE>> trackingSupports) {
        this.trackingSupports.addAll(trackingSupports);
    }

    /**
     * Removes one or more tracking support instances from the tracker.
     * Each specified tracking support instance provides functionality
     * for handling specific types of data and state management, and will
     * no longer be managed by the tracker after removal.
     *
     * @param trackingSupport an array of {@link GenericTrackingSupport} objects
     *                        representing the tracking behaviors and data handling
     *                        logic to be removed from the tracker.
     */
    @SafeVarargs
    public final void removeTrackingSupport(GenericTrackingSupport<DATA, STATE>... trackingSupport) {
        removeTrackingSupport(Arrays.asList(trackingSupport));
    }

    /**
     * Removes a list of tracking support instances from the tracker. Each specified tracking support
     * instance provides functionality for handling specific types of data and state management, and
     * will no longer be managed by the tracker after removal.
     *
     * @param trackingSupports a list of {@link GenericTrackingSupport} objects representing the
     *                         tracking behaviors and data handling logic to be removed from the tracker.
     */
    public final void removeTrackingSupport(List<GenericTrackingSupport<DATA, STATE>> trackingSupports) {
        trackingSupports.forEach(this.trackingSupports::remove);
    }

    /**
     * Executes the tracking process for all configured {@link GenericTrackingSupport} instances.
     * This method iterates through the {@code trackingSupports} collection and invokes the tracking logic
     * defined by each {@link GenericTrackingSupport} implementation. For each instance:
     * - It logs the start of the tracking process.
     * - It calls the {@code doTracking} method to process data and update the state.
     * - It logs the completion of the tracking process with relevant timestamps.
     *
     * If no tracking supports are configured, a warning is logged and the method exits early.
     * If an {@link IOException} occurs during processing, the error is logged but the process continues
     * for the remaining tracking supports.
     */
    public void track() {
        if (trackingSupports.isEmpty()) {
            log.warn("No tracking support configured");
            return;
        }

        for (GenericTrackingSupport<DATA, STATE> trackingSupport : trackingSupports) {
            try {
                log.info("Starting tracking for {}", trackingSupport.getName());
                OffsetDateTime lastTimestampDate = doTracking(trackingSupport);
                log.info("finished {} until: {}", trackingSupport.getName(), dateFormat.format(lastTimestampDate));
            } catch (IOException e) {
                log.error("Error tracking {}: {}", trackingSupport.getName(), e.getMessage(), e);
            }
        }
    }

    protected OffsetDateTime doTracking(GenericTrackingSupport<DATA, STATE> trackingSupport) throws IOException {
        STATE relationsTx = transactionStatusIndexService.getState();
        Tx tx = txStatusIndexService.getState();

        OffsetDateTime lastTimestampDate = OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(Optional.ofNullable(relationsTx).map(trackingSupport::lastTimestampSupplier).orElse(0L)),
                ZoneOffset.UTC);

        log.info("{} starting from: {}", trackingSupport.getName(), dateFormat.format(lastTimestampDate));

        int i = 0;
        do {
            List<DATA> trackingData = trackingSupport.getData(lastTimestampDate, batchSize);
            // filter data that are newer than the last transaction commit time from the main tracker
            List<DATA> filteredRelationData = trackingData
                    .stream()
                    .filter(x -> trackingSupport.getTimestamp(x) < tx.getTxnCommitTime())
                    .toList();

            if (trackingData.isEmpty()) {
                log.info("{} no new data found", trackingSupport.getName());
                break;
            }

            DATA lastData = filteredRelationData.get(filteredRelationData.size() - 1);
            lastTimestampDate = Instant.ofEpochMilli(trackingSupport.getTimestamp(lastData)).atOffset(ZoneOffset.UTC);

            trackingSupport.onHandleData(filteredRelationData);
            log.info("{} handled {} entries", trackingSupport.getName(), filteredRelationData.size());
            transactionStatusIndexService.setState(trackingSupport.stateApplier(relationsTx, lastTimestampDate.toInstant().toEpochMilli()));
        } while (i++ < maxIterations);

        return lastTimestampDate;
    }

}


