package org.edu_sharing.elasticsearch.tracker;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldSort;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.AlfrescoWebscriptClient;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.elasticsearch.core.SearchHitsRunner;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationCompletedAware;
import org.edu_sharing.elasticsearch.elasticsearch.core.model.ElasticNode;
import org.edu_sharing.elasticsearch.elasticsearch.utils.DataBuilder;
import org.edu_sharing.elasticsearch.metric.MetricContextHolder;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static org.edu_sharing.elasticsearch.metric.MetricContextHolder.MetricContext.PROGRESS_FACTOR;

@Component
@RequiredArgsConstructor
@Slf4j
public class CascadeTracker implements MigrationCompletedAware {

    public final static String propCascadeTx = "sys:cascadeTx";
    public final static String propDbid ="sys:node-dbid";
    public final static String elasticPropCascadeTx = "properties." + propCascadeTx + ".keyword";
    public final static String flag = "pathUpdateRequired";
    public final static String aspect = "sys:cascadeUpdate";

    private final WorkspaceService workspaceService;
    private final AlfrescoWebscriptClient alfrescoWebscriptClient;

    private boolean migrated = false;

    private final Query allCascadeQuery = QueryBuilders.exists(e -> e.field(elasticPropCascadeTx));

    private final Query processedCascadeQuery = QueryBuilders.bool(b -> b
            .must(m -> m.exists(e -> e.field(elasticPropCascadeTx)))
            .must(m -> m.term(t -> t.field(flag).value(false))));

    private final Query updatedCascadeQuery = QueryBuilders.bool()
            // for existing
            .should(s -> s
                    .bool(b -> b
                            .must(m -> m.exists(e -> e.field(elasticPropCascadeTx)))
                            .mustNot(m -> m.exists(e -> e.field(flag)))
                    )
            )
            // for new ones
            .should(s -> s
                    .bool(b -> b
                            .must(m -> m.exists(e -> e.field(elasticPropCascadeTx)))
                            .must(m -> m.term(t -> t.field(flag).value(true)))
                    )
            )
            .minimumShouldMatch("1").build()._toQuery();

    private final SortOptions resolveCascadeSortOptions = SortOptions.of(s -> s
            .field(FieldSort.of(f -> f
                    .field(elasticPropCascadeTx)
                    .order(SortOrder.Asc))));

    long metricCalculated = 0;

    public void track(){

        if(!migrated){
            return;
        }

        log.info("Track start");
        try{
            new SearchHitsRunner(workspaceService).run(
                    updatedCascadeQuery,
                    100,
                    null,
                    List.of(resolveCascadeSortOptions),
                    ElasticNode.class,
                    h -> processCascade(h.source()));
            calcMetric();
        }catch(ElasticsearchException e){
            if(e.error()!=null ){
                if(e.error().toString().contains("No mapping found for [properties.sys:cascadeTx.keyword]")) {
                    log.warn("No mapping found for [properties.sys:cascadeTx.keyword]. presumable new index.");
                    return;
                }
                log.error(e.error().toString(),e);
            }
            throw e;
        }catch (IOException e){
            log.error(e.getMessage(),e);
        }

    }

    private void processCascade(ElasticNode movedNode) throws IOException {
        long cascadeTxId = Long.parseLong((String) movedNode.getProperties().get(propCascadeTx));
        String nodeId = movedNode.getNodeRef().getId();

        Query resolveChildrenQuery = QueryBuilders.term(t -> t
                .field("path")
                .value(movedNode.getNodeRef().getId()));
        SearchHitsRunner searchHitsRunner = new SearchHitsRunner(workspaceService);
        searchHitsRunner.run(resolveChildrenQuery,100,null,null, ElasticNode.class, h ->{
            processCascadeChild(movedNode,h.source());
        });

        // mark as done: update status flag to processed if cascadeTxId did not change again
        new SearchHitsRunner(workspaceService).run(QueryBuilders.ids(i -> i.values(nodeId)),1,1,null, ElasticNode.class, h ->{
            long cascadeTxIdAfter = Long.parseLong((String)h.source().getProperties().get(CascadeTracker.propCascadeTx));
            if(cascadeTxIdAfter == cascadeTxId){
                DataBuilder dataBuilder = new DataBuilder();
                dataBuilder.startObject();
                dataBuilder.field(flag,false);
                dataBuilder.endObject();
                workspaceService.update(nodeId, dataBuilder.build());
            }
        });

        calcMetric();
    }

    private void calcMetric() throws IOException {
        if(System.currentTimeMillis() - metricCalculated > 5000) {
            workspaceService.refreshWorkspace();
            long processed = workspaceService.search(processedCascadeQuery, 0, 0).total().value();
            long all = workspaceService.search(allCascadeQuery, 0, 0).total().value();

            double progress = calcProgress(processed, all);
            MetricContextHolder.getCascadeContext().getProgress().set((long) (progress * PROGRESS_FACTOR));
            MetricContextHolder.getCascadeContext().getTimestamp().set(System.currentTimeMillis());
            log.info("{} processed {}%", MetricContextHolder.getCascadeContext().getLabelProgress(), Tools.df.format(progress));
            metricCalculated = System.currentTimeMillis();
        }
    }

    private Double calcProgress(long hitsProcessed, long all){
        return (double) hitsProcessed / all * 100.0d;
    }

    private void processCascadeChild(ElasticNode parent, ElasticNode child) throws IOException {
        long childId = Long.parseLong((String)child.getProperties().get(propDbid));
        List<NodeMetadata> nodeMetadataByIds = alfrescoWebscriptClient.getNodeMetadataByIds(List.of(childId));

        if(nodeMetadataByIds != null && !nodeMetadataByIds.isEmpty()){
            NodeMetadata nodeMetadata = nodeMetadataByIds.get(0);
            // ignore childs recently updated, will be processed by DefaultTransactionTracker
            if(parent.getTxnId() > nodeMetadata.getTxnId()){
                log.info("updating child: name: {}, childDbid: {}, childTxnId: {}, parentId: {}, parentTxId: {}",nodeMetadata.getProperties().get(CCConstants.CM_NAME), nodeMetadata.getId(), nodeMetadata.getTxnId(),parent.getProperties().get(propDbid), parent.getTxnId());
                DataBuilder builder = new DataBuilder();
                builder.startObject();
                workspaceService.addNodePath(builder, nodeMetadata);
                builder.endObject();
                workspaceService.update(Tools.getUUID(nodeMetadata.getNodeRef()),builder.build());
            }
        }
    }


    @Override
    public void MigrationCompleted() {
        migrated = true;
    }
}
