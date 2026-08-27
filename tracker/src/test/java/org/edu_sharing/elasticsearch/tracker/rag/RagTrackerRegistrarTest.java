package org.edu_sharing.elasticsearch.tracker.rag;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers what the registrar decides before any bean exists: which profiles are configured, which one
 * serves search, and what each of them is named.
 * <p>
 * The naming is not cosmetic. {@code TrackerBeanPostProcessor} names a tracker after its bean and
 * {@code TrackerExecutorFactory} derives its cursor document id from that name, so the bean name is
 * what gives each profile its own position in the transaction log. Two profiles that normalised to
 * the same name would share a cursor and quietly corrupt each other's progress.
 */
class RagTrackerRegistrarTest {

    private static MockEnvironment environment(String... properties) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < properties.length; i += 2) {
            environment.setProperty(properties[i], properties[i + 1]);
        }
        return environment;
    }

    private static MockEnvironment twoProfiles(String firstActive, String secondActive) {
        return environment(
                "tracker.rag.profiles[0].id", "bge-m3-v1",
                "tracker.rag.profiles[0].active", firstActive,
                "tracker.rag.profiles[0].model", "BAAI/bge-m3",
                "tracker.rag.profiles[0].dimensions", "1024",
                "tracker.rag.profiles[0].base-url", "http://embedding:8080",
                "tracker.rag.profiles[1].id", "gte-base-v1",
                "tracker.rag.profiles[1].active", secondActive,
                "tracker.rag.profiles[1].model", "Alibaba-NLP/gte-multilingual-base",
                "tracker.rag.profiles[1].dimensions", "768",
                "tracker.rag.profiles[1].base-url", "http://embedding-gte:8080");
    }

    private static RagProfile profile(String id, boolean active) {
        return new RagProfile(id, active, "BAAI/bge-m3", 1024, null, "http://embedding:8080",
                null, null, null, null, null, null, null);
    }

    // ---- binding ------------------------------------------------------------------------------

    @Test
    void bindsEveryConfiguredProfile() {
        List<RagProfile> profiles = RagTrackerRegistrar.bindProfiles(twoProfiles("true", "false"));

        assertThat(profiles).hasSize(2);
        assertThat(profiles.get(0).id()).isEqualTo("bge-m3-v1");
        assertThat(profiles.get(0).dimensions()).isEqualTo(1024);
        assertThat(profiles.get(1).dimensions()).isEqualTo(768);
    }

    @Test
    void fillsInTheDocumentedDefaults() {
        RagProfile profile = RagTrackerRegistrar.bindProfiles(twoProfiles("true", "false")).get(0);

        assertThat(profile.similarity()).isEqualTo("cosine");
        assertThat(profile.batchSize()).isEqualTo(32);
        assertThat(profile.maxChunksPerNode()).isEqualTo(300);
        assertThat(profile.toChunkingOptions().targetTokens()).isEqualTo(450);
        assertThat(profile.toEmbeddingProperties().timeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void refusesToStartWithoutAnyProfile() {
        assertThatThrownBy(() -> RagTrackerRegistrar.bindProfiles(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tracker.rag.profiles");
    }

    @Test
    void refusesAProfileWithoutTheModelsVectorLength() {
        // guessing this wrong is only noticed when the first bulk write is rejected
        MockEnvironment environment = environment(
                "tracker.rag.profiles[0].id", "bge-m3-v1",
                "tracker.rag.profiles[0].model", "BAAI/bge-m3",
                "tracker.rag.profiles[0].base-url", "http://embedding:8080");

        // Spring wraps the constructor's complaint in a BindException, so look at the cause
        assertThatThrownBy(() -> RagTrackerRegistrar.bindProfiles(environment))
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensions");
    }

    // ---- exactly one active -------------------------------------------------------------------

    @Test
    void acceptsExactlyOneActiveProfile() {
        RagProfile active = RagTrackerRegistrar.requireExactlyOneActive(
                List.of(profile("bge-m3-v1", true), profile("gte-base-v1", false)));

        assertThat(active.id()).isEqualTo("bge-m3-v1");
    }

    @Test
    void refusesTwoActiveProfiles() {
        // which index answers would depend on bean ordering - the ambiguity the alias removes
        assertThatThrownBy(() -> RagTrackerRegistrar.requireExactlyOneActive(
                List.of(profile("a", true), profile("b", true))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void refusesWhenNoProfileIsActive() {
        assertThatThrownBy(() -> RagTrackerRegistrar.requireExactlyOneActive(
                List.of(profile("a", false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly one");
    }

    @Test
    void refusesProfileIdsThatCollideAfterNormalisation() {
        // "bge m3" and "bge-m3" would produce the same bean name and share a cursor
        assertThatThrownBy(() -> RagTrackerRegistrar.requireExactlyOneActive(
                List.of(profile("bge m3", true), profile("bge-m3", false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unique");
    }

    // ---- naming -------------------------------------------------------------------------------

    @Test
    void givesEveryProfileItsOwnTrackerAndCursor() {
        RagProfile first = profile("bge-m3-v1", true);
        RagProfile second = profile("gte-base-v1", false);

        assertThat(RagTrackerRegistrar.trackerName(first)).isEqualTo("ragTracker_bge-m3-v1");
        assertThat(RagTrackerRegistrar.trackerName(second)).isEqualTo("ragTracker_gte-base-v1");
        assertThat(RagTrackerRegistrar.trackerName(first))
                .isNotEqualTo(RagTrackerRegistrar.trackerName(second));
    }

    @Test
    void reducesAProfileIdToWhatAnIndexNameCanCarry() {
        assertThat(profile("BGE m3 / v1", true).slug()).isEqualTo("bge-m3-v1");
        assertThat(profile("--weird--", true).slug()).isEqualTo("weird");
    }

    @Test
    void namesIndexAndServicesPerProfile() {
        RagProfile profile = profile("bge-m3-v1", true);

        assertThat(RagTrackerRegistrar.indexName(profile)).isEqualTo("ragChunks_bge-m3-v1");
        assertThat(RagTrackerRegistrar.chunkServiceName(profile)).isEqualTo("ragChunkService_bge-m3-v1");
        assertThat(RagTrackerRegistrar.embeddingServiceName(profile))
                .isEqualTo("ragEmbeddingService_bge-m3-v1");
    }
}
