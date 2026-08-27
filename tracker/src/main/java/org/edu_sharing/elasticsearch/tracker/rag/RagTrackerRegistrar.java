package org.edu_sharing.elasticsearch.tracker.rag;

import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkIndexConfiguration;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkService;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagIndexMetadata;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationInfo;
import org.edu_sharing.elasticsearch.rag.chunking.ChunkingService;
import org.edu_sharing.elasticsearch.rag.embedding.OpenAiCompatibleEmbeddingClient;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Registers one index and one tracker per embedding profile.
 * <p>
 * The point of doing this dynamically is a single line of behaviour that already exists:
 * {@code TrackerBeanPostProcessor} names a tracker after its bean, and
 * {@code TrackerExecutorFactory} derives the id of its cursor document from that name. Register a
 * tracker as {@code ragTracker_<profile>} and it gets its own cursor for free - a new profile starts
 * at zero and builds up while the old one keeps its position and keeps serving search. A rollback
 * resumes the old cursor exactly where it stopped.
 * <p>
 * A {@link BeanDefinitionRegistryPostProcessor} rather than a {@code @Configuration}, because the
 * number of beans depends on configuration and {@code @Bean} methods are fixed at compile time. The
 * profiles are therefore bound straight from the {@link Environment}: no bean exists yet at this
 * point.
 */
@Slf4j
public class RagTrackerRegistrar implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    static final String PROFILES_PROPERTY = "tracker.rag.profiles";
    static final String ALIAS_PROPERTY = "tracker.rag.alias";
    static final String DEFAULT_ALIAS = "rag_chunks";

    /** Bean name of the shared, stateless chunker. */
    static final String CHUNKING_SERVICE = "ragChunkingService";

    /** Reads the effective access from the workspace index; shared by every profile. */
    static final String ACCESS_RESOLVER = "ragAccessResolver";

    /** Carries access changes into the active profile's index. */
    static final String ACCESS_SYNC = "ragAccessSync";

    /** Bound on how many nodes one ACL or membership event may refresh at once. */
    static final String MAX_NODES_PROPERTY = "tracker.rag.access.maxNodesPerEvent";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        List<RagProfile> profiles = bindProfiles(environment);
        RagProfile active = requireExactlyOneActive(profiles);
        String alias = environment.getProperty(ALIAS_PROPERTY, DEFAULT_ALIAS);
        int shards = index(environment, "shards", "elastic.index.rag.number_of_shards",
                "elastic.index.number_of_shards");
        int replicas = index(environment, "replicas", "elastic.index.rag.number_of_replicas",
                "elastic.index.number_of_replicas");

        // the registry is the bean factory; suppliers below run at creation time, when it is live
        ConfigurableListableBeanFactory factory = (ConfigurableListableBeanFactory) registry;

        for (RagProfile profile : profiles) {
            register(registry, factory, profile, shards, replicas);
            log.info("rag profile '{}': model={} dimensions={} active={}",
                    profile.id(), profile.model(), profile.dimensions(), profile.active());
        }

        registry.registerBeanDefinition(CHUNKING_SERVICE,
                BeanDefinitionBuilder.genericBeanDefinition(ChunkingService.class).getBeanDefinition());

        registry.registerBeanDefinition(ACCESS_RESOLVER,
                BeanDefinitionBuilder.genericBeanDefinition(RagAccessResolver.class)
                        .addConstructorArgReference("client")
                        .addConstructorArgReference("workspace")
                        .getBeanDefinition());

        // only the active profile: the alias decides what search reads, so refreshing access on an
        // index nobody queries would be paid for and never used
        registry.registerBeanDefinition(ACCESS_SYNC,
                BeanDefinitionBuilder.genericBeanDefinition(RagAccessSync.class)
                        .addConstructorArgValue(environment.getProperty(MAX_NODES_PROPERTY, Integer.class, 50000))
                        .addConstructorArgReference(chunkServiceName(active))
                        .addConstructorArgReference(ACCESS_RESOLVER)
                        .getBeanDefinition());

        registry.registerBeanDefinition("ragAliasInitializer",
                BeanDefinitionBuilder.genericBeanDefinition(RagAliasInitializer.class)
                        .addConstructorArgReference(chunkServiceName(active))
                        .addConstructorArgValue(alias)
                        .addConstructorArgValue(metadataOf(active))
                        .getBeanDefinition());
    }

    private void register(BeanDefinitionRegistry registry, ConfigurableListableBeanFactory factory,
                          RagProfile profile, int shards, int replicas) {
        AbstractBeanDefinition index = BeanDefinitionBuilder
                .genericBeanDefinition(IndexConfiguration.class)
                .getBeanDefinition();
        index.setInstanceSupplier(() -> RagChunkIndexConfiguration.create(
                indexName(factory, profile), metadataOf(profile), shards, replicas));
        registry.registerBeanDefinition(indexName(profile), index);

        registry.registerBeanDefinition(chunkServiceName(profile),
                BeanDefinitionBuilder.genericBeanDefinition(RagChunkService.class)
                        .addConstructorArgReference("client")
                        .addConstructorArgReference(indexName(profile))
                        .getBeanDefinition());

        AbstractBeanDefinition embedding = BeanDefinitionBuilder
                .genericBeanDefinition(OpenAiCompatibleEmbeddingClient.class)
                .getBeanDefinition();
        embedding.setInstanceSupplier(
                () -> new OpenAiCompatibleEmbeddingClient(profile.toEmbeddingProperties()));
        registry.registerBeanDefinition(embeddingServiceName(profile), embedding);

        registry.registerBeanDefinition(trackerName(profile),
                BeanDefinitionBuilder.genericBeanDefinition(RagTracker.class)
                        .addConstructorArgReference("ragTrackerProperties")
                        .addConstructorArgValue(profile)
                        .addConstructorArgReference(CHUNKING_SERVICE)
                        .addConstructorArgReference(embeddingServiceName(profile))
                        .addConstructorArgReference(chunkServiceName(profile))
                        .addConstructorArgReference(ACCESS_RESOLVER)
                        .getBeanDefinition());
    }

    /**
     * The physical index name. The migration version is only known once {@code MigrationInfo} beans
     * exist, which is why this is resolved from inside the instance supplier rather than here.
     */
    private static String indexName(ConfigurableListableBeanFactory factory, RagProfile profile) {
        // The newest version is the last of the @Order-ed beans, exactly as AutoConfigurationTracker
        // resolves it. Comparing the strings instead would rank "9.1" above "11.0" and name the
        // index after a version the rest of the installation left behind long ago.
        String version = factory.getBeanProvider(MigrationInfo.class).orderedStream()
                .map(MigrationInfo::getVersion)
                .reduce((earlier, later) -> later)
                .orElseThrow(() -> new IllegalStateException("no MigrationInfo bean, cannot name the index"));
        return "rag_chunks_" + version + "_" + profile.slug();
    }

    static List<RagProfile> bindProfiles(Environment environment) {
        List<RagProfile> profiles = Binder.get(environment)
                .bind(PROFILES_PROPERTY, Bindable.listOf(RagProfile.class))
                .orElse(List.of());
        if (profiles.isEmpty()) {
            throw new IllegalStateException("tracker.rag.enabled is true but no "
                    + PROFILES_PROPERTY + " are configured");
        }
        return profiles;
    }

    /**
     * Exactly one profile serves search. Zero would leave the alias dangling; two would make which
     * index answers depend on bean ordering, which is precisely the ambiguity the alias exists to
     * remove.
     */
    static RagProfile requireExactlyOneActive(List<RagProfile> profiles) {
        List<RagProfile> active = profiles.stream().filter(RagProfile::active).toList();
        if (active.size() != 1) {
            throw new IllegalStateException("exactly one of " + PROFILES_PROPERTY
                    + " must have active=true, found " + active.size()
                    + " of " + profiles.size());
        }
        List<String> ids = profiles.stream().map(RagProfile::slug).distinct().toList();
        if (ids.size() != profiles.size()) {
            throw new IllegalStateException("profile ids must be unique after normalisation: " + ids);
        }
        return active.get(0);
    }

    private static int index(Environment environment, String what, String specific, String fallback) {
        String value = environment.getProperty(specific, environment.getProperty(fallback));
        if (value == null) {
            throw new IllegalStateException("neither " + specific + " nor " + fallback + " is set ("
                    + what + ")");
        }
        return Integer.parseInt(value.trim());
    }

    /** The embedding client normalises every vector, so the index says so too. */
    static RagIndexMetadata metadataOf(RagProfile profile) {
        return new RagIndexMetadata(profile.id(), profile.model(), profile.dimensions(),
                profile.similarity(), true);
    }

    static String indexName(RagProfile profile) {
        return "ragChunks_" + profile.slug();
    }

    static String chunkServiceName(RagProfile profile) {
        return "ragChunkService_" + profile.slug();
    }

    static String embeddingServiceName(RagProfile profile) {
        return "ragEmbeddingService_" + profile.slug();
    }

    /** Becomes the tracker's name and with it the id of its cursor document. */
    static String trackerName(RagProfile profile) {
        return "ragTracker_" + profile.slug();
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // nothing to do; everything happens while the registry is still open
    }
}
