package org.edu_sharing.elasticsearch.tracker.core.config;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Duration;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class AlfTransactionTrackerProperties extends BaseTrackerProperties {
    private String storeProtocol;
    private String storeIdentifier;

    private List<String> indexStoreRefs = List.of();
    private List<String> workspaceTypes = List.of();
    private List<String> workspaceSubTypes = List.of();

    private List<String> includeNodeTypes = List.of();
    private List<String> excludeNodeTypes = List.of();
    private List<String> includeAspects = List.of();
    private List<String> excludeAspects = List.of();

    private Duration timeStep = Duration.ofHours(1);
    private int threads = 1;

    private int numberOfTransactions = 200;
    private int fetchSizeAlfresco = 100;
    private int bulkSizeElastic = 50;
}
