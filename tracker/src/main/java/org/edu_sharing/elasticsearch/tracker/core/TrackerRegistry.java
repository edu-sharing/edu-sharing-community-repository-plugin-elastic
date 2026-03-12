package org.edu_sharing.elasticsearch.tracker.core;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class TrackerRegistry {

    private final List<TrackerConfig<?, ?>> allTrackerConfigs;

    private final List<TrackerCoroutineConfig> allTrackerCoroutineConfigs;

    /**
     * Retrieves a set of active tracker configurations from the list of all tracker configurations.
     * A tracker configuration is considered active if its associated properties indicate it is enabled.
     *
     * @return a set of {@link TrackerConfig} instances where the corresponding property is enabled
     */
    public Set<TrackerConfig<?, ?>> getActiveTrackerConfigs() {
        return allTrackerConfigs.stream()
                .filter(x -> x.getConfig().isEnabled())
                .collect(Collectors.toSet());
    }

    /**
     * Retrieves a list of active tracker coroutine configurations from the collection of all tracker coroutine configurations.
     * A tracker coroutine configuration is considered active if its associated properties indicate it is enabled.
     *
     * @return a list of {@link TrackerCoroutineConfig} instances where the corresponding property is enabled
     */
    public List<TrackerCoroutineConfig> getActiveTrackerCoroutineConfigs() {
        return allTrackerCoroutineConfigs.stream()
                .filter(x -> x.getConfig().isEnabled())
                .collect(Collectors.toList());
    }

    /**
     * Retrieves a {@link TrackerConfig} instance by its name from the collection of tracker configurations.
     *
     * @param part the name of the tracker configuration to retrieve; must not be null or empty
     * @return the {@link TrackerConfig} instance matching the provided name
     * @throws IllegalArgumentException if no tracker configuration is found with the specified name
     */
    @NotNull
    public TrackerConfig<?, ?> getTrackerConfigByName(@NonNull @NotNull String part) {
        return allTrackerConfigs.stream()
                .filter(x -> x.getName().equals(part))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tracker config not found for name: " + part));
    }

    public <STATE> TrackerConfig<?, STATE> getTrackerConfigByClass(Class<? extends TrackerConfig<?, STATE>> trackerConfigClass) {
        return allTrackerConfigs.stream()
                .filter(x -> x.getClass().equals(trackerConfigClass))
                .map(trackerConfigClass::cast)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tracker config not found for class: " + trackerConfigClass));
    }
}
