package org.edu_sharing.elasticsearch.tracker.preview;

import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodePreview;
import org.edu_sharing.elasticsearch.elasticsearch.utils.DataBuilder;
import org.edu_sharing.elasticsearch.tracker.core.AbstractAlfTransactionTracker;
import org.edu_sharing.elasticsearch.tracker.core.config.AlfTransactionTrackerProperties;
import org.edu_sharing.elasticsearch.tracker.utils.Partition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PreviewTracker extends AbstractAlfTransactionTracker<AlfTransactionTrackerProperties> {
    public PreviewTracker(AlfTransactionTrackerProperties previewTrackerProps) {
        super(previewTrackerProps);
    }

    @Override
    public void trackNodes(List<Node> nodes) throws IOException {

        Collection<List<Node>> partitions = Partition.getPartitions(nodes, this.props.getFetchSizeAlfresco());
        for (List<Node> partition : partitions) {
            List<BulkOperation> updates = Collections.synchronizedList(new ArrayList<>());
            for (Node node : partition) {
                threadUtil.getThreadPool().execute(() -> {
                    NodePreview previewData = eduSharingService.getPreviewDataByNodeRef(node.getNodeRef());
                    DataBuilder builder = new DataBuilder();

                    builder.startObject()
                            .startObject("preview").
                            field("mimetype", previewData.getMimetype()).
                            field("type", previewData.getType()).
                            field("icon", previewData.isIcon()).
                            field("small", previewData.getSmall()).
                            //field("large", nodeData.getNodePreview().getLarge()).
                            endObject()
                        .endObject();
                    BulkOperation bulkOp = BulkOperation.of(b -> b
                            .update(u -> u
                                    .id(Long.valueOf(node.getId()).toString())
                                    .action(a -> a
                                            .doc(builder.build())
                                    )
                            )
                    );
                    updates.add(bulkOp);
                });
            }
            if (!threadUtil.getThreadPool().awaitQuiescence(10, TimeUnit.MINUTES)) {
                log.error("Fatal error while processing nodes: timeout of preview and transform processing");
                log.error(partition.stream().map(Node::getNodeRef).collect(Collectors.joining(", ")));
            }
            workspaceService.updateBulk(updates);
        }
    }
}
