package org.edu_sharing.elasticsearch.elasticsearch.core.state;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserActivityTx {
    private Long lastTimestamp;
}
