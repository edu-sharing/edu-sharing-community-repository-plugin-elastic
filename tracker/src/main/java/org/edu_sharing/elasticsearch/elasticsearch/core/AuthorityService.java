package org.edu_sharing.elasticsearch.elasticsearch.core;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.edu_sharing.elasticsearch.alfresco.client.NodeData;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.elasticsearch.utils.DataBuilder;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class AuthorityService {

    private final ElasticsearchClient client;
    private final String index;

    WorkspaceService workspaceService;

    private final Logger logger = LogManager.getLogger(AuthorityService.class);


    AuthorityService(ElasticsearchClient client, IndexConfiguration authorities, WorkspaceService workspaceService) {
        this.client = client;
        this.index = authorities.getIndex();
        this.workspaceService = workspaceService;
    }


    public void index(List<NodeData> nodes) throws IOException {
        List<BulkOperation> operations = new ArrayList<>();
        for (NodeData nodeData : nodes) {
            NodeMetadata node = nodeData.getNodeMetadata();
            DataBuilder builder = new DataBuilder();
            workspaceService.fillData(nodeData, builder);
            Object data = builder.build();
            operations.add(BulkOperation.of(op -> op.index(iop -> iop
                    .index(index)
                    .id(Long.toString(node.getId()))
                    .document(data))));
        }

        if (!operations.isEmpty()) {
            logger.info("starting bulk update:");
            BulkResponse bulkResponse = client.bulk(req -> req.index(index).operations(operations));
            logger.info("finished bulk update:");

            logger.info("start refresh index");
            this.refreshIndex();
            try {
                for (BulkResponseItem item : bulkResponse.items()) {
                    if (item.error() != null) {
                        logger.error("Failed indexing of " + item.id());
                        logger.error("Failed indexing of " + item.error().causedBy());
                    }
                }
            } catch (Throwable e) {
                logger.error(e.getMessage(), e);
                throw e;
            }

        }
        logger.info("returning");
    }

    public void refreshIndex() throws IOException {
        logger.debug("starting");
        client.indices().refresh(req -> req.index(index));
        logger.debug("returning");
    }
}
