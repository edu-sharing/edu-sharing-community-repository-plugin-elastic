package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import lombok.RequiredArgsConstructor;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.AclTx;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.StatisticTimestamp;
import org.edu_sharing.elasticsearch.elasticsearch.core.state.Tx;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class StatusIndexServiceFactory {

    private final ElasticsearchClient client;

    public StatusIndexService<Tx> createTransactionStateService(String index) {
        return new StatusIndexService<>(index, client, Tx::new, "1", Tx.class);
    }

    public StatusIndexService<Tx> createTransactionStateService(String index, String id) {
        return new StatusIndexService<>(index, client, Tx::new, id, Tx.class);
    }

    public StatusIndexService<AclTx> createAclStateService(String index) {
        return new StatusIndexService<>(index, client, AclTx::new, "2", AclTx.class);
    }

    public StatusIndexService<StatisticTimestamp> createStatisticTimestampStateService(String index) {
        return new StatusIndexService<>(index, client, StatisticTimestamp::new, "3", StatisticTimestamp.class);
    }
}
