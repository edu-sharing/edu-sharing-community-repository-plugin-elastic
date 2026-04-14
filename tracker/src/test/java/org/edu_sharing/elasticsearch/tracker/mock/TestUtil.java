package org.edu_sharing.elasticsearch.tracker.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.edu_sharing.elasticsearch.alfresco.client.Transactions;

public class TestUtil {
    public static Transactions loadTransactions(String file) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(
                TestUtil.class.getClassLoader().getResourceAsStream(file),
                Transactions.class
        );
    }
}
