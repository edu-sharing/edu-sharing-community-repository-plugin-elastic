package org.edu_sharing.elasticsearch.tracker.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.GetMappingRequest;
import co.elastic.clients.elasticsearch.indices.GetMappingResponse;
import co.elastic.clients.elasticsearch.indices.get_mapping.IndexMappingRecord;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesRequest;
import co.elastic.clients.elasticsearch.indices.UpdateAliasesResponse;
import co.elastic.clients.util.ObjectBuilder;
import org.edu_sharing.elasticsearch.alfresco.client.AlfrescoApi;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.core.AuthorityService;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.elasticsearch.core.NodeFailureService;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkService;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagIndexMetadata;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationInfo;
import org.edu_sharing.elasticsearch.tracker.core.TrackerBeanPostProcessor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

import java.io.IOException;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Boots a real (if small) Spring context, because the registrar's whole job only happens at context
 * startup and the unit tests around it cover nothing but its static helpers.
 * <p>
 * What is actually being proven here: two configured profiles produce two trackers under two
 * distinct bean names - which is what gives each of them its own cursor document - two indices, and
 * an alias pointed at the one marked active. A mistake in any of that would not show up until the
 * application starts against a real cluster.
 */
class RagContextWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // the real application.properties, so the @Value fields of the mocked collaborators
            // resolve against the same defaults production uses
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(TrackerDependencies.class, RagConfiguration.class)
            .withPropertyValues(
                    "tracker.rag.enabled=true",
                    "elastic.index.number_of_shards=1",
                    "elastic.index.number_of_replicas=1",
                    "tracker.rag.profiles[0].id=bge-m3-v1",
                    "tracker.rag.profiles[0].active=true",
                    "tracker.rag.profiles[0].model=BAAI/bge-m3",
                    "tracker.rag.profiles[0].dimensions=1024",
                    "tracker.rag.profiles[0].base-url=http://embedding:8080",
                    "tracker.rag.profiles[1].id=gte-base-v1",
                    "tracker.rag.profiles[1].active=false",
                    "tracker.rag.profiles[1].model=Alibaba-NLP/gte-multilingual-base",
                    "tracker.rag.profiles[1].dimensions=768",
                    "tracker.rag.profiles[1].base-url=http://embedding-gte:8080");

    @Test
    void registersOneTrackerPerProfileUnderItsOwnName() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(RagTracker.class))
                    .containsOnlyKeys("ragTracker_bge-m3-v1", "ragTracker_gte-base-v1");
        });
    }

    @Test
    void givesEachTrackerTheNameItsCursorIsDerivedFrom() {
        // TrackerBeanPostProcessor is what does this; the point is that it applies to these beans too
        runner.run(context -> assertThat(context.getBean("ragTracker_gte-base-v1", RagTracker.class)
                .getName()).isEqualTo("ragTracker_gte-base-v1"));
    }

    @Test
    void registersOneIndexPerProfileWithTheProfilesDimensions() {
        runner.run(context -> {
            Map<String, IndexConfiguration> indices = context.getBeansOfType(IndexConfiguration.class);

            assertThat(indices).containsKeys("ragChunks_bge-m3-v1", "ragChunks_gte-base-v1");
            // 11.0, not 9.1: the newest version is the last ordered bean. Comparing the strings
            // would pick "9.1" and name the index after a version the installation left behind.
            assertThat(indices.get("ragChunks_bge-m3-v1").getIndex()).isEqualTo("rag_chunks_11.0_bge-m3-v1");
            assertThat(indices.get("ragChunks_gte-base-v1").getIndex()).isEqualTo("rag_chunks_11.0_gte-base-v1");
        });
    }

    @Test
    void bindsEachChunkServiceToItsOwnIndex() {
        runner.run(context -> {
            assertThat(context.getBean("ragChunkService_bge-m3-v1", RagChunkService.class).getIndex())
                    .isEqualTo("rag_chunks_11.0_bge-m3-v1");
            assertThat(context.getBean("ragChunkService_gte-base-v1", RagChunkService.class).getIndex())
                    .isEqualTo("rag_chunks_11.0_gte-base-v1");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void pointsTheAliasAtTheActiveProfileOnStartup() {
        runner.run(context -> {
            ElasticsearchIndicesClient indices = context.getBean(ElasticsearchIndicesClient.class);
            ArgumentCaptor<Function<UpdateAliasesRequest.Builder, ObjectBuilder<UpdateAliasesRequest>>> captor =
                    ArgumentCaptor.forClass(Function.class);

            verify(indices).updateAliases(captor.capture());
            UpdateAliasesRequest request = captor.getValue().apply(new UpdateAliasesRequest.Builder()).build();

            assertThat(request.actions()).hasSize(2);
            assertThat(request.actions().get(1).add().index()).isEqualTo("rag_chunks_11.0_bge-m3-v1");
            assertThat(request.actions().get(1).add().alias()).isEqualTo("rag_chunks");
        });
    }

    @Test
    void registersTheAccessSyncForTheActiveProfileOnly() {
        // refreshing access on an index nobody queries would be paid for and never used
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(RagAccessSync.class)).hasSize(1);
            assertThat(context.getBeansOfType(RagAccessResolver.class)).hasSize(1);
        });
    }

    @Test
    void refusesToServeAnIndexBuiltByADifferentModel() {
        // AdminService only creates missing indices, so pointing a profile at an existing name
        // silently reuses whatever is in it - and vectors from two models share no space
        runner.withPropertyValues("tracker.rag.profiles[0].dimensions=384").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("not comparable");
        });
    }

    @Test
    void staysCompletelyOutOfTheWayWhenSwitchedOff() {
        runner.withPropertyValues("tracker.rag.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(RagTracker.class)).isEmpty();
            assertThat(context.getBeansOfType(RagAccessSync.class)).isEmpty();
            assertThat(context.getBeansOfType(IndexConfiguration.class))
                    .containsOnlyKeys("workspace");
        });
    }

    @Test
    void refusesToStartWhenNoProfileServesSearch() {
        runner.withPropertyValues("tracker.rag.profiles[0].active=false").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("exactly one");
        });
    }

    /** The collaborators {@code AbstractAlfTransactionTracker} autowires, as mocks. */
    @Configuration
    static class TrackerDependencies {

        @Bean
        static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        /** The real one: it is what turns a bean name into the tracker's cursor document id. */
        @Bean
        static TrackerBeanPostProcessor trackerBeanPostProcessor() {
            return new TrackerBeanPostProcessor();
        }

        @Bean
        ElasticsearchClient client() throws IOException {
            ElasticsearchClient client = mock(ElasticsearchClient.class);
            ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
            when(client.indices()).thenReturn(indices);
            when(indices.updateAliases(any(Function.class)))
                    .thenReturn(UpdateAliasesResponse.of(b -> b.acknowledged(true)));
            // the alias initializer verifies the index was built by the configured model before
            // pointing search at it
            when(indices.getMapping(any(Function.class))).thenAnswer(invocation -> {
                GetMappingRequest request = ((Function<GetMappingRequest.Builder,
                        ObjectBuilder<GetMappingRequest>>) invocation.getArgument(0))
                        .apply(new GetMappingRequest.Builder()).build();
                String name = request.index().get(0);
                int dims = name.endsWith("gte-base-v1") ? 768 : 1024;
                String model = name.endsWith("gte-base-v1")
                        ? "Alibaba-NLP/gte-multilingual-base" : "BAAI/bge-m3";
                return GetMappingResponse.of(b -> b.result(name, IndexMappingRecord.of(r -> r
                        .mappings(m -> m.meta(new RagIndexMetadata(
                                "p", model, dims, "cosine", true).toMeta())))));
            });
            return client;
        }

        @Bean
        ElasticsearchIndicesClient indicesClient(ElasticsearchClient client) {
            return client.indices();
        }

        @Bean
        RagTrackerProperties ragTrackerProperties() {
            return new RagTrackerProperties();
        }

        /** The access resolver reads the effective readers from here. */
        @Bean
        IndexConfiguration workspace() {
            return new IndexConfiguration(req -> req.index("workspace_11.0"));
        }

        /**
         * Two versions on purpose, and in an order where a string comparison gets it wrong: "9.1"
         * sorts above "11.0". The newest version is the last @Order-ed bean, not the largest string.
         */
        @Bean
        @org.springframework.core.annotation.Order(0)
        MigrationInfo migration91() {
            return MigrationInfo.builder().version("9.1").build();
        }

        @Bean
        @org.springframework.core.annotation.Order(1)
        MigrationInfo migration11() {
            return MigrationInfo.builder().version("11.0").build();
        }

        @Bean
        AlfrescoApi alfrescoApi() {
            return mock(AlfrescoApi.class);
        }

        @Bean
        WorkspaceService workspaceService() {
            return mock(WorkspaceService.class);
        }

        @Bean
        AuthorityService authorityService() {
            return mock(AuthorityService.class);
        }

        @Bean
        EduSharingService eduSharingService() {
            return mock(EduSharingService.class);
        }

        @Bean
        NodeFailureService nodeFailureService() {
            return mock(NodeFailureService.class);
        }
    }
}
