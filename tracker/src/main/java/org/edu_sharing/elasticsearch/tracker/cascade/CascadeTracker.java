package org.edu_sharing.elasticsearch.tracker.cascade;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldSort;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.alfresco.client.AlfrescoWebscriptClient;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.elasticsearch.core.SearchHitsRunner;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.model.ElasticNode;
import org.edu_sharing.elasticsearch.elasticsearch.utils.DataBuilder;
import org.edu_sharing.elasticsearch.metric.MetricContext;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.elasticsearch.tracker.core.AbstractTrackerCoroutine;
import org.edu_sharing.elasticsearch.tracker.core.TrackingContext;
import org.edu_sharing.elasticsearch.tracker.core.config.BaseTrackerProperties;
import org.edu_sharing.elasticsearch.tracker.core.config.TrackerScheduleProperties;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

import static org.edu_sharing.elasticsearch.metric.MetricContext.PROGRESS_FACTOR;


// TODO should be separted form the other trackers becaause it's an state independent post process
@Slf4j
@Component
public class CascadeTracker extends AbstractTrackerCoroutine<TrackerScheduleProperties> {

    public final static String propCascadeTx = "sys:cascadeTx";
    public final static String propDbid = "sys:node-dbid";
    public final static String elasticPropCascadeTx = "properties." + propCascadeTx + ".keyword";
    public final static String flag = "pathUpdateRequired";

    private final WorkspaceService workspaceService;
    private final AlfrescoWebscriptClient alfrescoWebscriptClient;
    private final SearchHitsRunner searchHitsRunner;


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

    public CascadeTracker(BaseTrackerProperties cascadeTrackerProps, WorkspaceService workspaceService, AlfrescoWebscriptClient alfrescoWebscriptClient, SearchHitsRunner searchHitsRunner) {
        super(cascadeTrackerProps);
        this.workspaceService = workspaceService;
        this.alfrescoWebscriptClient = alfrescoWebscriptClient;
        this.searchHitsRunner = searchHitsRunner;
    }

    @Override
    public State track(TrackingContext<Void> trackingContext) {
        log.info("Track coroutine");
        try {
            searchHitsRunner.run(
                    updatedCascadeQuery,
                    100,
                    null,
                    List.of(resolveCascadeSortOptions),
                    ElasticNode.class,
                    h -> processCascade(h.source(), trackingContext));
            calcMetric(trackingContext.metricContext());
            return State.FINISHED;
        } catch (ElasticsearchException e) {
            if (e.error() != null) {
                if (e.error().toString().contains("No mapping found for [properties.sys:cascadeTx.keyword]")) {
                    log.warn("No mapping found for [properties.sys:cascadeTx.keyword]. presumable new index.");
                    return State.EXCEPTION;
                }
                log.error(e.error().toString(), e);
            }
            throw e;
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return State.EXCEPTION;
        }

    }

    private void processCascade(ElasticNode movedNode, TrackingContext<?> trackingContext) throws IOException {
        long cascadeTxId = Long.parseLong((String) movedNode.getProperties().get(propCascadeTx));
        String nodeId = movedNode.getNodeRef().getId();

        Query resolveChildrenQuery = QueryBuilders.term(t -> t
                .field("path")
                .value(movedNode.getNodeRef().getId()));
        searchHitsRunner.run(resolveChildrenQuery, 100, ElasticNode.class, h -> {
            processCascadeChild(movedNode, h.source());
        });

        // mark as done: update status flag to processed if cascadeTxId did not change again
        searchHitsRunner.run(QueryBuilders.ids(i -> i.values(nodeId)), 1, 1, null, ElasticNode.class, h -> {
            long cascadeTxIdAfter = Long.parseLong((String) h.source().getProperties().get(CascadeTracker.propCascadeTx));
            if (cascadeTxIdAfter == cascadeTxId) {
                DataBuilder dataBuilder = new DataBuilder();
                dataBuilder.startObject();
                dataBuilder.field(flag, false);
                dataBuilder.endObject();
                workspaceService.update(nodeId, dataBuilder.build());
            }
        });

        calcMetric(trackingContext.metricContext());
    }

    private void calcMetric(MetricContext metricContext) throws IOException {
        if (System.currentTimeMillis() - metricCalculated > 5000) {
            workspaceService.refreshWorkspace();
            long processed = workspaceService.search(processedCascadeQuery, 0, 0).total().value();
            long all = workspaceService.search(allCascadeQuery, 0, 0).total().value();

            double progress = calcProgress(processed, all);
            metricContext.getProgress().set((long) (progress * PROGRESS_FACTOR));
            metricContext.getTimestamp().set(System.currentTimeMillis());
            log.info("{} processed {}%", metricContext.getLabelProgress(), Tools.df.format(progress));
            metricCalculated = System.currentTimeMillis();
        }
    }

    private Double calcProgress(long hitsProcessed, long all) {
        return (double) hitsProcessed / all * 100.0d;
    }

    private void processCascadeChild(ElasticNode parent, ElasticNode child) throws IOException {
        long childId = Long.parseLong((String) child.getProperties().get(propDbid));
        List<NodeMetadata> nodeMetadataByIds = alfrescoWebscriptClient.getNodeMetadataByIds(List.of(childId));

        if (nodeMetadataByIds != null && !nodeMetadataByIds.isEmpty()) {
            NodeMetadata nodeMetadata = nodeMetadataByIds.get(0);
            // ignore childs recently updated, will be processed by DefaultTransactionTracker
            if (parent.getTxnId() > nodeMetadata.getTxnId()) {
                log.info("updating child: name: {}, childDbid: {}, childTxnId: {}, parentId: {}, parentTxId: {}", nodeMetadata.getProperties().get(CCConstants.CM_NAME), nodeMetadata.getId(), nodeMetadata.getTxnId(), parent.getProperties().get(propDbid), parent.getTxnId());
                DataBuilder builder = new DataBuilder();
                builder.startObject();
                workspaceService.addNodePath(builder, nodeMetadata);
                builder.endObject();
                workspaceService.update(Tools.getUUID(nodeMetadata.getNodeRef()), builder.build());
            }
        }
    }
}
