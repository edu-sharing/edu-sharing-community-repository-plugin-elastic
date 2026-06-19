package org.edu_sharing.elasticsearch.tracker.content;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@EqualsAndHashCode(callSuper = true)
@ConfigurationProperties(prefix = "tracker.content")
public class ContentTrackerProperties extends AlfTransactionTrackerProperties {
    public enum Api {
        Alfresco,
        EduSharing
    }
    private Api api = Api.Alfresco;
    private int maxContentLength;
}


