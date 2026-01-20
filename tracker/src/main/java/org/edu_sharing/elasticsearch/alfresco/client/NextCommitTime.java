package org.edu_sharing.elasticsearch.alfresco.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class NextCommitTime {
    private long nextTransactionCommitTimeMs;
}
