package org.edu_sharing.elasticsearch.tracker.mock;

import org.edu_sharing.elasticsearch.alfresco.client.Transactions;
import tools.jackson.databind.json.JsonMapper;

public class TestUtil {
    public static Transactions loadTransactions(String file) throws Exception {
        JsonMapper mapper = JsonMapper.builderWithJackson2Defaults().build();
        return mapper.readValue(
                TestUtil.class.getClassLoader().getResourceAsStream(file),
                Transactions.class
        );
    }
}
