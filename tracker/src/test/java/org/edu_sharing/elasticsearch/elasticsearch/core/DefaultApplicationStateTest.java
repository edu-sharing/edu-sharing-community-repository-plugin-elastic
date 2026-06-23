package org.edu_sharing.elasticsearch.elasticsearch.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultApplicationStateTest {

    @Test
    void canTrackOnlyWhenMigrationHooksAndRepositoryAreReady() {
        // Arrange
        DefaultApplicationState state = new DefaultApplicationState();

        // Act & Assert
        assertThat(state.canTrack()).isFalse();

        state.markMigrationCompleted();
        assertThat(state.canTrack()).isFalse();

        state.markHooksCompleted();
        assertThat(state.canTrack()).isFalse();

        state.markRepositoryReady();
        assertThat(state.canTrack()).isTrue();
    }
}