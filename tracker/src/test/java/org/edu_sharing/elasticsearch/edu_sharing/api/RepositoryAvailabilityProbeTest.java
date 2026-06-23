package org.edu_sharing.elasticsearch.edu_sharing.api;

import org.edu_sharing.generated.repository.backend.services.rest.client.api.AboutApi;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.About;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryAvailabilityProbeTest {

    @Mock
    private AboutApi aboutApi;

    private RepositoryAvailabilityProbe underTest;

    @BeforeEach
    void setUp() {
        underTest = new RepositoryAvailabilityProbe(aboutApi);
        ReflectionTestUtils.setField(underTest, "pollInterval", Duration.ofMillis(1));
    }

    @Test
    void isAvailableReturnsTrueWhenRepositoryAnswers() {
        // Arrange
        when(aboutApi.about()).thenReturn(Mono.just(new About()));

        // Act
        boolean available = underTest.isAvailable();

        // Assert
        assertThat(available).isTrue();
    }

    @Test
    void isAvailableReturnsFalseWhenRepositoryErrors() {
        // Arrange
        when(aboutApi.about()).thenReturn(Mono.error(new RuntimeException("connection refused")));

        // Act
        boolean available = underTest.isAvailable();

        // Assert
        assertThat(available).isFalse();
    }

    @Test
    void waitUntilAvailableBlocksUntilRepositoryBecomesAvailable() {
        // Arrange
        when(aboutApi.about())
                .thenReturn(Mono.error(new RuntimeException("connection refused")))
                .thenReturn(Mono.error(new RuntimeException("connection refused")))
                .thenReturn(Mono.just(new About()));

        // Act
        underTest.waitUntilAvailable();

        // Assert
        verify(aboutApi, times(3)).about();
    }
}