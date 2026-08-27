package org.edu_sharing.elasticsearch.tracker.rag;

import org.edu_sharing.elasticsearch.alfresco.client.NodeData;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.alfresco.client.Path;
import org.edu_sharing.elasticsearch.alfresco.client.Reader;
import org.edu_sharing.elasticsearch.elasticsearch.core.RagChunkMetadata;
import org.edu_sharing.elasticsearch.rag.chunking.ChunkSource;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapper makes one decision worth pinning down: facets are stored raw while the chunk source
 * gets the resolved labels.
 * <p>
 * Storing labels in the facets would break filter compatibility with the workspace index, which
 * holds the vocabulary URIs. Embedding the URIs instead of the labels would be worse - nobody
 * searches for {@code .../discipline/380}, and a model cannot place it either.
 */
class RagNodeMapperTest {

    private static final String DISCIPLINE = "http://w3id.org/openeduhub/vocabs/discipline/380";

    private static NodeData node(Map<String, Serializable> properties,
                                 Map<String, Map<String, List<String>>> valueSpaces) {
        NodeMetadata metadata = new NodeMetadata();
        metadata.setId(4711L);
        metadata.setNodeRef("workspace://SpacesStore/uuid-1");
        metadata.setType("ccm:io");
        metadata.setAclId(42L);
        metadata.setOwner("admin");
        metadata.setAspects(Set.of("ccm:eduscope"));
        metadata.setProperties(properties);

        Path path = new Path();
        path.setApath("/store/company_home/folder/uuid-1");
        metadata.setPaths(List.of(path));

        Reader reader = new Reader();
        reader.setReaders(List.of("GROUP_EVERYONE", "admin"));

        NodeData data = new NodeData();
        data.setNodeMetadata(metadata);
        data.setReader(reader);
        data.setFullText("Der Bruch wird gekuerzt.");
        if (valueSpaces != null) {
            data.setValueSpaces(valueSpaces);
        }
        return data;
    }

    private static Map<String, Serializable> properties() {
        Map<String, Serializable> properties = new LinkedHashMap<>();
        properties.put("{http://www.campuscontent.de/model/lom/1.0}title", "Bruchrechnen");
        properties.put("{http://www.campuscontent.de/model/lom/1.0}general_description", "Ein Arbeitsblatt.");
        properties.put("{http://www.campuscontent.de/model/1.0}taxonid", DISCIPLINE);
        properties.put("{http://www.campuscontent.de/model/1.0}commonlicense_key", "CC_BY");
        return properties;
    }

    private static Map<String, Map<String, List<String>>> germanLabels() {
        return Map.of("de_DE", Map.of(
                "{http://www.campuscontent.de/model/1.0}taxonid", List.of("Mathematik")));
    }

    @Test
    void embedsTheResolvedLabelRatherThanTheVocabularyUri() {
        ChunkSource source = RagNodeMapper.toChunkSource(node(properties(), germanLabels()));

        assertThat(source.subject()).containsExactly("Mathematik");
    }

    @Test
    void storesTheRawVocabularyUriAsTheFacet() {
        // the workspace index holds the URI, so a filter has to mean the same thing here
        RagChunkMetadata metadata = RagNodeMapper.toMetadata(node(properties(), germanLabels()));

        assertThat(metadata.facets().subject()).containsExactly(DISCIPLINE);
        assertThat(metadata.facets().license()).isEqualTo("CC_BY");
    }

    @Test
    void fallsBackToTheRawValueWhenNoLabelWasResolved() {
        ChunkSource source = RagNodeMapper.toChunkSource(node(properties(), null));

        assertThat(source.subject()).containsExactly(DISCIPLINE);
    }

    @Test
    void carriesTitleAndDescriptionIntoTheChunkSource() {
        ChunkSource source = RagNodeMapper.toChunkSource(node(properties(), germanLabels()));

        assertThat(source.title()).isEqualTo("Bruchrechnen");
        assertThat(source.description()).isEqualTo("Ein Arbeitsblatt.");
        assertThat(source.nodeId()).isEqualTo("uuid-1");
    }

    @Test
    void fallsBackToTheFileNameWhenThereIsNoTitle() {
        Map<String, Serializable> properties = new LinkedHashMap<>();
        properties.put("{http://www.alfresco.org/model/content/1.0}name", "arbeitsblatt.pdf");

        assertThat(RagNodeMapper.toChunkSource(node(properties, null)).title())
                .isEqualTo("arbeitsblatt.pdf");
    }

    @Test
    void doesNotTryToBuildAccessFromNodeDataAlone() {
        // who may see a node depends on the collections holding a copy of it, and NodeData knows
        // nothing about those - RagAccessResolver reads them from the workspace index
        RagChunkMetadata metadata = RagNodeMapper.toMetadata(node(properties(), null));

        assertThat(metadata).isNotNull();
        assertThat(metadata.aclId()).isEqualTo(42L);
    }

    @Test
    void buildsThePathTheSameWayTheWorkspaceIndexDoes() {
        // WorkspaceService.addNodePath drops the leading empty element and joins with slashes
        RagChunkMetadata metadata = RagNodeMapper.toMetadata(node(properties(), null));

        assertThat(metadata.fullpath()).isEqualTo("store/company_home/folder/uuid-1");
        assertThat(metadata.fullpaths()).containsExactly("store/company_home/folder/uuid-1");
    }

    @Test
    void carriesIdentityAndAccessKeys() {
        RagChunkMetadata metadata = RagNodeMapper.toMetadata(node(properties(), null));

        assertThat(metadata.dbid()).isEqualTo(4711L);
        assertThat(metadata.aclId()).isEqualTo(42L);
        assertThat(metadata.owner()).isEqualTo("admin");
        assertThat(metadata.type()).isEqualTo("ccm:io");
    }

    @Test
    void survivesANodeWithoutProperties() {
        NodeData bare = node(new LinkedHashMap<>(), null);

        assertThat(RagNodeMapper.toChunkSource(bare).title()).isNull();
        assertThat(RagNodeMapper.toMetadata(bare).facets().license()).isNull();
    }

    @Test
    void metadataFingerprintIgnoresNothingThatIsStored() {
        RagChunkMetadata original = RagNodeMapper.toMetadata(node(properties(), null));

        Map<String, Serializable> relicensed = properties();
        relicensed.put("{http://www.campuscontent.de/model/1.0}commonlicense_key", "CC_BY_SA");
        RagChunkMetadata changed = RagNodeMapper.toMetadata(node(relicensed, null));

        // a licence correction has to reach the index, and it must not cost a re-embedding
        assertThat(changed.fingerprint()).isNotEqualTo(original.fingerprint());
    }

    @Test
    void metadataFingerprintIsStableAcrossCalls() {
        assertThat(RagNodeMapper.toMetadata(node(properties(), null)).fingerprint())
                .isEqualTo(RagNodeMapper.toMetadata(node(properties(), null)).fingerprint());
    }
}
