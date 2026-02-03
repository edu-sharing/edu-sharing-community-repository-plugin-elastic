package org.edu_sharing.elasticsearch.tracker.generic;


import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.elasticsearch.core.StatusIndexService;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class GenericTimebaseTrackerFactory {

    private final StatusIndexService<Tx> txStatusIndexService;


    @Bean(autowireCandidate = false, defaultCandidate = false)
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public <DATA, STATE> GenericTimebaseTracker<DATA, STATE> createTracker(StatusIndexService<STATE> statusIndexService) {
        return new GenericTimebaseTracker<>(statusIndexService, txStatusIndexService);
    }
}
