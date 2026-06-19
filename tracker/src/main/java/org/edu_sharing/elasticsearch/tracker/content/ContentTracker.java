package org.edu_sharing.elasticsearch.tracker.content;

import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.json.JsonData;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.edu_sharing.elasticsearch.alfresco.client.FetchParameters;
import org.edu_sharing.elasticsearch.alfresco.client.Node;
import org.edu_sharing.elasticsearch.alfresco.client.NodeData;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.elasticsearch.utils.DataBuilder;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.core.AbstractAlfTransactionTracker;
import org.edu_sharing.elasticsearch.tracker.utils.Partition;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

@Slf4j
@Component
public class ContentTracker extends AbstractAlfTransactionTracker<ContentTrackerProperties> {

    @Value("${tracker.bulk.size.elastic}")
    int bulkSizeElastic;

    public ContentTracker(ContentTrackerProperties contentTrackerProps) {
        super(contentTrackerProps);
    }

    @Override
    public void trackNodes(List<Node> nodes) throws IOException {
        List<NodeMetadata> nodeMetadata = getNodeMetadata(nodes);
        List<NodeData> nodeDatas = getNodeData(nodeMetadata);
        index(nodeDatas);
    }

    private void index(List<NodeData> nodeDatas) throws IOException {
        Collection<List<NodeData>> partitions = Partition.getPartitions(nodeDatas, this.bulkSizeElastic);
        for (List<NodeData> partition : partitions) {
            List<BulkOperation> updates = Collections.synchronizedList(new ArrayList<>());
            for (NodeData nodeData : partition){
                //content
                /*
                 *     "{http://www.alfresco.org/model/content/1.0}content": {
                 *    "contentId": "279",
                 *    "encoding": "UTF-8",
                 *    "locale": "de_DE_",
                 *    "mimetype": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                 *    "size": "8385"
                 * },
                 */
                @SuppressWarnings("unchecked")
                Map<String, Object> content = (Map<String, Object>) nodeData.getNodeMetadata().getProperties().get("{http://www.alfresco.org/model/content/1.0}content");
                if (content != null || !StringUtils.isBlank(nodeData.getFullText())) {
                    DataBuilder builder = new DataBuilder();
                    builder.startObject();
                    if (content != null){
                        builder.field("contentId", content.get("contentId"));
                        builder.field("encoding", content.get("encoding"));
                        builder.field("locale", content.get("locale"));
                        builder.field("mimetype", content.get("mimetype"));
                        builder.field("size", content.get("size"));
                    }

                    if (nodeData.getFullText() != null) {
                        if (this.props.getMaxContentLength() > 0 && nodeData.getFullText().length() > this.props.getMaxContentLength()) {
                            log.info("Node {} has too large fulltext: {}. Will be truncated to {}", nodeData.getNodeMetadata().getNodeRef(), nodeData.getFullText().length(), this.props.getMaxContentLength());
                            builder.field("fulltext", nodeData.getFullText().substring(0, this.props.getMaxContentLength()));
                        } else {
                            builder.field("fulltext", nodeData.getFullText());
                        }
                    }
                    builder.endObject();

                    BulkOperation bulkOp = BulkOperation.of(b -> b
                            .update(u -> u
                                    .id(Long.valueOf(nodeData.getNodeMetadata().getId()).toString())
                                    .action(a -> a.script(
                                            s -> s
                                                    .source("ctx._source.content = params.new_content")
                                                    .params("new_content", JsonData.of(builder.build()))
                                    ))
                            )
                    );
                    updates.add(bulkOp);
                }else{
                    log.debug("Node {} has no content. content will be removed", nodeData.getNodeMetadata().getNodeRef());
                    BulkOperation bulkOp = BulkOperation.of(b -> b
                            .update(u -> u
                                    .id(Long.valueOf(nodeData.getNodeMetadata().getId()).toString())
                                    .action(a -> a.script(
                                            s -> s
                                                    .source("ctx._source.remove('content');")
                                    ))
                            )
                    );
                    updates.add(bulkOp);
                }
            }
            workspaceService.updateBulk(updates);
        }
    }

    @NotNull
    private List<NodeMetadata> getNodeMetadata(List<Node> nodes) throws IOException {
        Collection<List<Node>> partitions = Partition.getPartitions(nodes, this.props.getFetchSizeAlfresco());
        List<NodeMetadata> nodeMetadata = Collections.synchronizedList(new ArrayList<>());
        this.threadUtil.runThreaded(
                partitions.stream().toList(),
                p -> nodeMetadata.addAll(alfClient.getNodeMetadata(p)),
                true,
                true);
        return nodeMetadata;
    }

    private List<NodeData> getNodeData(List<NodeMetadata> nodeMetadata) throws IOException {
        Collection<List<NodeMetadata>> partitions = Partition.getPartitions(nodeMetadata, props.getFetchSizeAlfresco());
        List<NodeData> nodeData = Collections.synchronizedList(new ArrayList<>());
        this.threadUtil.runThreaded(partitions.stream().toList(), p ->{
            List<NodeData> fetched = alfClient.getNodeData(p, FetchParameters.MINIMAL);
            for(NodeData nd : fetched){
                String fullText = null;
                try {
                    if(ContentTrackerProperties.Api.Alfresco.equals(this.props.getApi())) {
                        fullText = this.alfClient.getTextContent(nd.getNodeMetadata().getId());
                    }else if(ContentTrackerProperties.Api.EduSharing.equals(this.props.getApi())){
                        fullText = this.eduSharingService.getTextContent(Tools.getUUID(nd.getNodeMetadata().getNodeRef()));
                    }else log.warn("Unknown FullTextApi: " + this.props.getApi());
                }catch(Throwable t) {
                    log.warn("Error while fetching text content for " + nd.getNodeMetadata().getNodeRef(), t);
                }
                if (fullText != null) nd.setFullText(fullText);
            }
            nodeData.addAll(fetched);

        }, true, true);
        return nodeData;
    }


}