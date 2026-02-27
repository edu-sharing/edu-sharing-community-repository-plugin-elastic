package org.edu_sharing.elasticsearch.tracker.acl;


import lombok.Data;
import lombok.EqualsAndHashCode;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@EqualsAndHashCode(callSuper = true)
@ConfigurationProperties(prefix = "tracker.acl")
public class AclTrackerProperties extends AlfTransactionTrackerProperties {
    private Duration maxTimeStep = Duration.ofDays(32);

}


