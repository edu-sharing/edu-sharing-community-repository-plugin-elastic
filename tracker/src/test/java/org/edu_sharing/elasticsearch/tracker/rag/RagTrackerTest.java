package org.edu_sharing.elasticsearch.tracker.rag;

import org.edu_sharing.elasticsearch.alfresco.client.NodeData;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.alfresco.client.Path;
import org.edu_sharing.elasticsearch.alfresco.client.Reader;
import org.edu_sharing.elasticsearch.elasticsearch.core.NodeFailureService;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkDocument;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkService;
import org.edu_sharing.elasticsearch.rag.chunking.ChunkSource;
import org.edu_sharing.elasticsearch.rag.chunking.ChunkingService;
import org.edu_sharing.elasticsearch.rag.chunking.ContentFingerprint;
import org.edu_sharing.elasticsearch.rag.embedding.EmbeddingException;
import org.edu_sharing.elasticsearch.rag.embedding.EmbeddingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the three paths a node can take, which is where the tracker's cost lives.
 * <p>
 * The one that matters most is the third: a node whose text and metadata are both unchanged must
 * produce no write at all. ACL, collection and statistics changes touch nodes constantly, and in an
 * index holding vectors every rewrite is expensive - a tracker that re-embedded on every touch would
 * be unaffordable to run, and one that merely rewrote documents would still be far too slow.
 * <p>
 * Driven through the package-private seam rather than through {@code track()}, because the
 * transaction cursor and the Alfresco fetch belong to {@code AbstractAlfTransactionTracker} and are
 * covered by its own tests.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagTrackerTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private RagChunkService ragChunkService;

    @Mock
    private NodeFailureService nodeFailureService;

    @Mock
    private RagAccessResolver accessResolver;

    @Captor
    private ArgumentCaptor<List<RagChunkDocument>> documentsCaptor;

    @Captor
    private ArgumentCaptor<List<RagChunkService.MetadataUpdate>> refreshCaptor;

    private RagTracker tracker;
    private RagTrackerProperties properties;
    private RagProfile profile;

    /**
     * Null-safe on purpose: re-stubbing {@code embed} in a test invokes this answer once with a null
     * argument, which is Mockito's normal behaviour and not worth working around at every call site.
     */
    private static final Answer<List<float[]>> ONE_VECTOR_PER_TEXT = invocation -> {
        List<String> texts = invocation.getArgument(0);
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; texts != null && i < texts.size(); i++) {
            vectors.add(new float[]{1.0f, 0.0f});
        }
        return vectors;
    };

    @BeforeEach
    void setUp() throws IOException {
        properties = new RagTrackerProperties();
        properties.setThreads(1);
        profile = profile(300);
        tracker = new RagTracker(properties, profile, new ChunkingService(), embeddingService,
                ragChunkService, accessResolver);
        tracker.setNodeFailureService(nodeFailureService);
        tracker.setName("ragTracker");
        when(accessResolver.resolve(any())).thenReturn(Map.of());

        when(embeddingService.embed(any())).thenAnswer(ONE_VECTOR_PER_TEXT);
        when(ragChunkService.indexChunks(any())).thenReturn(Set.of());
        when(ragChunkService.updateMetadata(any())).thenReturn(Set.of());
    }

    private static RagProfile profile(int maxChunksPerNode) {
        return new RagProfile("bge-m3-v1", true, "BAAI/bge-m3", 2, "cosine",
                "http://localhost:8080", null, null, null, null, null, maxChunksPerNode, null);
    }

    private static NodeData node(String uuid, String title, String text, String license) {
        Map<String, Serializable> properties = new LinkedHashMap<>();
        properties.put("{http://www.campuscontent.de/model/lom/1.0}title", title);
        if (license != null) {
            properties.put("{http://www.campuscontent.de/model/1.0}commonlicense_key", license);
        }

        NodeMetadata metadata = new NodeMetadata();
        metadata.setId(1L);
        metadata.setNodeRef("workspace://SpacesStore/" + uuid);
        metadata.setType("ccm:io");
        metadata.setAclId(42L);
        metadata.setOwner("admin");
        metadata.setAspects(Set.of());
        metadata.setProperties(properties);
        Path path = new Path();
        path.setApath("/store/company_home/" + uuid);
        metadata.setPaths(List.of(path));

        Reader reader = new Reader();
        reader.setReaders(List.of("GROUP_EVERYONE"));

        NodeData data = new NodeData();
        data.setNodeMetadata(metadata);
        data.setReader(reader);
        data.setFullText(text);
        return data;
    }

    private static String contentHashOf(NodeData node) {
        return ContentFingerprint.of(RagNodeMapper.toChunkSource(node));
    }

    private static String metaHashOf(NodeData node) {
        return RagNodeMapper.toMetadata(node).fingerprint();
    }

    // ---- path 1: the text changed ------------------------------------------------------------

    @Test
    void embedsANodeTheIndexHasNeverSeen() throws IOException {
        NodeData node = node("uuid-1", "Bruchrechnen", "Der Bruch wird gekuerzt.", "CC_BY");
        when(ragChunkService.findIndexState(any())).thenReturn(Map.of());

        tracker.indexNodes(List.of(node));

        verify(ragChunkService).indexChunks(documentsCaptor.capture());
        assertThat(documentsCaptor.getValue()).isNotEmpty();
        assertThat(documentsCaptor.getValue()).allSatisfy(document -> {
            assertThat(document.nodeId()).isEqualTo("uuid-1");
            assertThat(document.embedding()).hasSize(2);
            assertThat(document.contentHash()).isEqualTo(contentHashOf(node));
        });
    }

    @Test
    void reEmbedsWhenTheTextChanged() throws IOException {
        NodeData node = node("uuid-1", "Bruchrechnen", "Ein anderer Text.", "CC_BY");
        when(ragChunkService.findIndexState(any())).thenReturn(Map.of("uuid-1",
                new RagChunkService.ChunkState("uuid-1", "veralteter-hash", metaHashOf(node), 2)));

        tracker.indexNodes(List.of(node));

        verify(embeddingService).embed(any());
        verify(ragChunkService).indexChunks(any());
    }

    // ---- path 2: only the filter fields changed ----------------------------------------------

    @Test
    void refreshesMetadataWithoutCallingTheModel() throws IOException {
        // a corrected licence has to reach the index; it is not a reason to embed anything again
        NodeData node = node("uuid-1", "Bruchrechnen", "Der Bruch wird gekuerzt.", "CC_BY_SA");
        when(ragChunkService.findIndexState(any())).thenReturn(Map.of("uuid-1",
                new RagChunkService.ChunkState("uuid-1", contentHashOf(node), "alter-meta-hash", 3)));

        tracker.indexNodes(List.of(node));

        verify(embeddingService, never()).embed(any());
        verify(ragChunkService, never()).indexChunks(any());
        verify(ragChunkService).updateMetadata(refreshCaptor.capture());
        assertThat(refreshCaptor.getValue()).singleElement().satisfies(update -> {
            assertThat(update.nodeId()).isEqualTo("uuid-1");
            // the stored count, so the existing chunks can be addressed without another query
            assertThat(update.chunkCount()).isEqualTo(3);
            assertThat(update.metadata().facets().license()).isEqualTo("CC_BY_SA");
        });
    }

    // ---- path 3: nothing changed -------------------------------------------------------------

    @Test
    void writesNothingWhenNeitherTextNorMetadataChanged() throws IOException {
        NodeData node = node("uuid-1", "Bruchrechnen", "Der Bruch wird gekuerzt.", "CC_BY");
        when(ragChunkService.findIndexState(any())).thenReturn(Map.of("uuid-1",
                new RagChunkService.ChunkState("uuid-1", contentHashOf(node), metaHashOf(node), 2)));

        tracker.indexNodes(List.of(node));

        verify(embeddingService, never()).embed(any());
        verify(ragChunkService, never()).indexChunks(any());
        verify(ragChunkService).updateMetadata(List.of());
    }

    // ---- housekeeping ------------------------------------------------------------------------

    @Test
    void removesTheChunksOfANodeThatLostItsContentAndMetadata() throws IOException {
        NodeData empty = node("uuid-1", null, null, null);
        when(ragChunkService.findIndexState(any())).thenReturn(Map.of("uuid-1",
                new RagChunkService.ChunkState("uuid-1", "alter-hash", "alter-meta", 2)));

        tracker.indexNodes(List.of(empty));

        verify(ragChunkService).deleteByNodeIds(List.of("uuid-1"));
    }

    @Test
    void deletesOrphansOnlyWhenTheDocumentGotShorter() throws IOException {
        NodeData node = node("uuid-1", "Bruchrechnen", "Kurz.", "CC_BY");
        when(ragChunkService.findIndexState(any())).thenReturn(Map.of("uuid-1",
                new RagChunkService.ChunkState("uuid-1", "alter-hash", "alter-meta", 99)));

        tracker.indexNodes(List.of(node));

        verify(ragChunkService).deleteOrphans(anyString(), anyInt());
    }

    @Test
    void doesNotQueryForOrphansWhenTheDocumentGrew() throws IOException {
        NodeData node = node("uuid-1", "Bruchrechnen", "Der Bruch wird gekuerzt.", "CC_BY");
        when(ragChunkService.findIndexState(any())).thenReturn(Map.of("uuid-1",
                new RagChunkService.ChunkState("uuid-1", "alter-hash", "alter-meta", 1)));

        tracker.indexNodes(List.of(node));

        verify(ragChunkService, never()).deleteOrphans(anyString(), anyInt());
    }

    @Test
    void skipsCollectionCopiesSoNothingIsEmbeddedTwice() throws IOException {
        // a copy carries the original's metadata shell; embedding it would put the same title into
        // the index once per collection membership
        NodeData original = node("uuid-1", "Bruchrechnen", "Der Bruch wird gekuerzt.", "CC_BY");
        NodeData kopie = node("uuid-2", "Bruchrechnen", "Der Bruch wird gekuerzt.", "CC_BY");
        kopie.getNodeMetadata().setAspects(Set.of(RagTracker.COLLECTION_COPY_ASPECT));
        when(ragChunkService.findIndexState(any())).thenReturn(Map.of());

        tracker.indexNodes(List.of(original, kopie));

        verify(ragChunkService).indexChunks(documentsCaptor.capture());
        assertThat(documentsCaptor.getValue()).extracting(RagChunkDocument::nodeId)
                .containsOnly("uuid-1");
    }

    // ---- failures ----------------------------------------------------------------------------

    @Test
    void recordsASingleUnusableNodeAndCarriesOn() throws IOException {
        // the client does not retry a 400, so without this the batch would replay forever
        NodeData poison = node("uuid-1", "Kaputt", "Ein Text.", "CC_BY");
        NodeData healthy = node("uuid-2", "Heil", "Ein anderer Text.", "CC_BY");
        when(ragChunkService.findIndexState(any())).thenReturn(Map.of());
        when(embeddingService.embed(any()))
                .thenThrow(new EmbeddingException("unknown model"))
                .thenAnswer(ONE_VECTOR_PER_TEXT);

        tracker.indexNodes(List.of(poison, healthy));

        verify(nodeFailureService).record(any(), anyString(), anyString());
        verify(ragChunkService).indexChunks(documentsCaptor.capture());
        assertThat(documentsCaptor.getValue()).extracting(RagChunkDocument::nodeId)
                .containsOnly("uuid-2");
    }

    @Test
    void treatsAWholeFailedBatchAsAnOutage() throws IOException {
        // if nothing embeds, the model is down - committing the cursor would skip these nodes
        when(ragChunkService.findIndexState(any())).thenReturn(Map.of());
        when(embeddingService.embed(any())).thenThrow(new EmbeddingException("connection refused"));

        assertThatThrownBy(() -> tracker.indexNodes(List.of(
                node("uuid-1", "Eins", "Ein Text.", "CC_BY"),
                node("uuid-2", "Zwei", "Noch ein Text.", "CC_BY"))))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("outage");

        verify(ragChunkService, never()).indexChunks(any());
    }

    @Test
    void reportsAnOversizedDocumentInsteadOfDroppingItSilently() throws IOException {
        tracker = new RagTracker(properties, profile(1), new ChunkingService(),
                embeddingService, ragChunkService, accessResolver);
        tracker.setNodeFailureService(nodeFailureService);
        tracker.setName("ragTracker");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            text.append("Abschnitt ").append(i).append(" erklaert das Kuerzen von Bruechen. ");
        }
        when(ragChunkService.findIndexState(any())).thenReturn(Map.of());

        tracker.indexNodes(List.of(node("uuid-1", "Lang", text.toString(), "CC_BY")));

        verify(nodeFailureService).record(any(), anyString(), anyString());
    }

    @Test
    void embedsTheContextHeaderRatherThanTheBareChunk() throws IOException {
        NodeData node = node("uuid-1", "Bruchrechnen", "Der Bruch wird gekuerzt.", "CC_BY");
        when(ragChunkService.findIndexState(any())).thenReturn(Map.of());
        ArgumentCaptor<List<String>> texts = ArgumentCaptor.forClass(List.class);

        tracker.indexNodes(List.of(node));

        verify(embeddingService).embed(texts.capture());
        verify(ragChunkService).indexChunks(documentsCaptor.capture());

        // what goes to the model carries the header, so an isolated paragraph still has a subject
        assertThat(texts.getValue()).isNotEmpty()
                .allSatisfy(text -> assertThat(text).contains("Bruchrechnen"));
        // what is stored and later shown stays bare
        assertThat(documentsCaptor.getValue())
                .filteredOn(document -> "CONTENT".equals(document.kind()))
                .allSatisfy(document -> assertThat(document.text()).doesNotContain("Bruchrechnen"));
    }
}
