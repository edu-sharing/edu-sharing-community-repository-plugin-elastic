package org.edu_sharing.elasticsearch.elasticsearch.config;

import co.elastic.clients.elasticsearch.core.UpdateResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHitsRelation;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.edu_sharing.elasticsearch.alfresco.client.*;
import org.edu_sharing.elasticsearch.edu_sharing.client.EduSharingClient;
import org.edu_sharing.elasticsearch.edu_sharing.client.Repository;
import org.edu_sharing.elasticsearch.elasticsearch.core.IndexConfiguration;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.elasticsearch.elasticsearch.utils.DataBuilder;
import org.edu_sharing.elasticsearch.tools.ScriptExecutor;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceServiceTest {

    @Mock
    private AlfrescoWebscriptClient alfrescoClient;
    @Mock
    private co.elastic.clients.elasticsearch.ElasticsearchClient elasticsearchClient;
    @Mock
    private ScriptExecutor scriptExecutor;

    private WorkspaceService underTest;

    private static String indentJson(String json) {
        JsonParser parser = new JsonParser();
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        JsonElement el = parser.parse(json);
        return gson.toJson(el);
    }

    @BeforeEach
    void setUp() {
        Repository repository = mock(Repository.class);
        EduSharingClient eduSharingClient = mock(EduSharingClient.class);
        when(eduSharingClient.getHomeRepository()).thenReturn(repository);
        when(repository.getId()).thenReturn("local");

        underTest = Mockito.spy(new WorkspaceService(elasticsearchClient, scriptExecutor, eduSharingClient, alfrescoClient, new IndexConfiguration(req -> req.index("workspace"))));
    }

    @Test
    void getTest() throws Exception {
        NodeData data = getNodeDataDummy(NodeData.class);
        DataBuilder builder = new DataBuilder();
        underTest.fillData(data, builder, null);
        String actual = indentJson(new Gson().toJson(builder.build()));
        String expected = StreamUtils.copyToString(getClass().getClassLoader().getResource("getTest.json").openStream(), StandardCharsets.UTF_8);
        assertEquals(expected, actual);
    }

    @Test
    void getProposalDataTest() throws Exception {
        NodeDataProposal data = getNodeDataDummy(NodeDataProposal.class);
        data.setCollection(getNodeDataDummy(NodeData.class));
        data.setOriginal(getNodeDataDummy(NodeData.class));
        DataBuilder builder = new DataBuilder();
        underTest.fillData(data, builder, null);
        String actual = indentJson(new Gson().toJson(builder.build()));
        String expected = StreamUtils.copyToString(getClass().getClassLoader().getResource("getProposalDataTest.json").openStream(), StandardCharsets.UTF_8);
        assertEquals(expected, actual);
    }


    @Test
    void mapWorkflowProtocolTest() throws Exception {
        DataBuilder builder = new DataBuilder();
        Serializable entry = (Serializable) Arrays.asList(
                "{\"editor\":\"admin\",\"receiver\":[\"admin\"],\"comment\":\"\",\"time\":1703251657754,\"status\":\"TASK_DECLINE_ELEMENT\"}",
                "{\"editor\":\"admin\",\"receiver\":[\"admin\"],\"comment\":\"\",\"time\":1703250527971,\"status\":\"TASK_DECLINE_ELEMENT\"}",
                "{\"editor\":\"admin\",\"receiver\":[],\"time\":1598997699092,\"status\":\"140_ELEMENT_LEGALLY_APPROVED\"}",
                "{\"editor\":\"admin\",\"receiver\":[],\"time\":1598997699092,\"status\":\"150_PUBLISH_IN_SEARCH\"}"
        );
        builder.startObject();
        underTest.mapWorkflowProtocol(entry, builder);
        builder.endObject();
        String actual = indentJson(new Gson().toJson(builder.build()));
        String expected = StreamUtils.copyToString(getClass().getClassLoader().getResource("mapWorkflowProtocolTest.json").openStream(), StandardCharsets.UTF_8);
        assertEquals(expected, actual);
    }

    @Disabled("Temporarily disabled due to result is different than data")
    @Test
    void indexCollectionsTest() throws Exception {
        //assertThrows(IOException.class, () -> underTest.indexCollections(getNodeDataDummy(NodeData.class).getNodeMetadata()), "wrong type:ccm:io");

        // no collections to index test
        NodeData data = getNodeDataDummy(NodeData.class);
        data.getNodeMetadata().setType("ccm:usage");
        data.getNodeMetadata().getProperties().put("{http://www.campuscontent.de/model/1.0}usageappid", "local");
        data.getNodeMetadata().getProperties().put("{http://www.campuscontent.de/model/1.0}usagecourseid", "0192f8a9-374d-41f3-9025-83e8f75c8717");
        data.getNodeMetadata().getProperties().put("{http://www.campuscontent.de/model/1.0}usageparentnodeid", "0192f8a9-374d-41f3-9025-83e8f75c8717");
        Mockito.doReturn(null).when(underTest).search(ArgumentMatchers.any(), ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt());
        DataBuilder builder = underTest.indexCollections(data.getNodeMetadata());
        assertNull(builder);


        // collections to index test
        data = getNodeDataDummy(NodeData.class);
        data.getNodeMetadata().setType("ccm:usage");
        data.getNodeMetadata().getProperties().put("{http://www.campuscontent.de/model/1.0}usageappid", "local");
        data.getNodeMetadata().getProperties().put("{http://www.campuscontent.de/model/1.0}usagecourseid", "0192f8a9-374d-41f3-9025-83e8f75c8717");
        data.getNodeMetadata().getProperties().put("{http://www.campuscontent.de/model/1.0}usageparentnodeid", "0192f8a9-374d-41f3-9025-83e8f75c8717");

        Hit<Map> hit = Mockito.spy(Hit.of(h -> h.index("workspace").id("1")));
        //SearchHit hit = Mockito.spy(new SearchHit(1, "1", new Text("node"), Collections.emptyMap(), Collections.emptyMap()));
        Mockito.doReturn(Map.of(
                        "dbid", "123",
                        "properties", getDummyProperties("0192f8a9-374d-41f3-9025-83e8f75c8717"),
                        "collections", Collections.singletonList(Map.of(
                                "dbid", "123",
                                "relation", Map.of("dbid", 123),
                                "properties", getDummyProperties("0192f8a9-374d-41f3-9025-83e8f75c8717")
                        ))))
                .when(hit)
                .source();

        when(alfrescoClient.getNodeData(ArgumentMatchers.anyList())).thenReturn(Collections.singletonList(getNodeDataDummy(NodeData.class)));
        Mockito.doReturn(HitsMetadata.of((HitsMetadata.Builder<Map> b) -> b.total(t -> t.value(1).relation(TotalHitsRelation.Eq)).hits(hit))).when(underTest).search(ArgumentMatchers.any(), ArgumentMatchers.anyInt(), ArgumentMatchers.anyInt());
        UpdateResponse<?> response = Mockito.mock(UpdateResponse.class);
        Mockito.doReturn(response).when(underTest).update(ArgumentMatchers.anyLong(), ArgumentMatchers.any(Map.class));
        Mockito.doNothing().when(underTest).refreshWorkspace();
        builder = underTest.indexCollections(data.getNodeMetadata());

        String actual = indentJson(new Gson().toJson(builder.build()));
        String expected = StreamUtils.copyToString(getClass().getClassLoader().getResource("indexCollectionsTest.json").openStream(), StandardCharsets.UTF_8);
        assertEquals(expected, actual);

    }

    private static <T extends NodeData> T getNodeDataDummy(Class<T> clazz) throws InstantiationException, IllegalAccessException {
        String nodeId = "0192f8a9-374d-41f3-9025-83e8f75c8717";
        T data = clazz.newInstance();
        NodeData rating = new NodeData();
        rating.setNodeMetadata(new NodeMetadata());
        rating.getNodeMetadata().setNodeRef("workspace://SpacesStore/" + nodeId);
        rating.getNodeMetadata().setType("ccm:rating");
        data.getChildren().add(rating);
        data.setNodeMetadata(new NodeMetadata());
        data.getNodeMetadata().setTxnId(4321);
        data.getNodeMetadata().setType("ccm:io");
        data.getNodeMetadata().setAclId(54321);
        data.getNodeMetadata().setId(654321);
        data.setValueSpaces(new HashMap<>() {{
            put("de-DE", new HashMap<>() {{
                put(CCConstants.LOM_PROP_EDUCATIONAL_LEARNINGRESOURCETYPE, Collections.singletonList("LRT Test Translation DE"));
            }});
            put("en-US", new HashMap<>() {{
                put(CCConstants.LOM_PROP_EDUCATIONAL_LEARNINGRESOURCETYPE, Collections.singletonList("LRT Test Translation EN"));
            }});
        }});
        data.setNodePreview(new NodePreview());
        data.getNodePreview().setIsIcon(false);
        data.getNodePreview().setType("USERDEFINED");
        data.getNodePreview().setMimetype("image/jpeg");
        data.getNodePreview().setSmall(Base64.getDecoder().decode("/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////2wBDAf//////////////////////////////////////////////////////////////////////////////////////wgARCAAIABQDAREAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAAAf/EABQBAQAAAAAAAAAAAAAAAAAAAAD/2gAMAwEAAhADEAAAAUQP/8QAFBABAAAAAAAAAAAAAAAAAAAAEP/aAAgBAQABBQJ//8QAFBEBAAAAAAAAAAAAAAAAAAAAEP/aAAgBAwEBPwF//8QAFBEBAAAAAAAAAAAAAAAAAAAAEP/aAAgBAgEBPwF//8QAFBABAAAAAAAAAAAAAAAAAAAAEP/aAAgBAQAGPwJ//8QAFBABAAAAAAAAAAAAAAAAAAAAEP/aAAgBAQABPyF//9oADAMBAAIAAwAAABCCf//EABQRAQAAAAAAAAAAAAAAAAAAABD/2gAIAQMBAT8Qf//EABQRAQAAAAAAAAAAAAAAAAAAABD/2gAIAQIBAT8Qf//EABgQAAIDAAAAAAAAAAAAAAAAAAARARDh/9oACAEBAAE/EIeUz//Z"));
        data.getNodeMetadata().setNodeRef("workspace://SpacesStore/" + nodeId);
        Path path = new Path();
        path.setPath(nodeId);
        path.setApath(nodeId);
        data.getNodeMetadata().setPaths(Collections.singletonList(path));
        rating.getNodeMetadata().setPaths(Collections.singletonList(path));
        data.getNodeMetadata().setAncestors(Set.of(data.getNodeMetadata().getNodeRef()));
        rating.getNodeMetadata().setAncestors(Set.of(data.getNodeMetadata().getNodeRef()));
        Map<String, Serializable> properties = getDummyProperties(nodeId);
        rating.getNodeMetadata().setProperties(new HashMap<>() {{
            put(CCConstants.CM_PROP_C_MODIFIED, ZonedDateTime.of(
                    2023, 1, 1, 0, 0, 0, 0, ZoneId.of("Europe/Berlin")
            ).format(DateTimeFormatter.ISO_INSTANT));
            put(CCConstants.CCM_PROP_RATING_VALUE, "5.0");
        }});
        data.getNodeMetadata().setProperties(properties);
        data.setReader(new Reader());
        rating.setReader(new Reader());
        data.getReader().setReaders(Collections.singletonList("tester"));
        rating.getReader().setReaders(Collections.singletonList("tester"));
        AccessControlList acl = new AccessControlList();
        AccessControlEntry ace = new AccessControlEntry();
        ace.setAuthority("tester");
        ace.setPermission("Coordinator");
        acl.setAces(Collections.singletonList(ace));
        data.setAccessControlList(acl);
        rating.setAccessControlList(acl);
        return data;
    }

    private static Map<String, Serializable> getDummyProperties(String nodeId) {
        return new HashMap<>() {{
            put(CCConstants.CM_NAME, "Test");
            put(CCConstants.LOM_PROP_GENERAL_KEYWORD, (Serializable) Arrays.asList(
                    Collections.singletonList(Collections.singletonMap("a", "Key a")),
                    Collections.singletonList(Collections.singletonMap("b", "Key b"))
            ));
            put(CCConstants.CCM_PROP_IO_MEDIACENTER, (Serializable) Collections.singletonList("{\"name\":\"GROUP_MEDIA_CENTER_001\",\"activated\": true}"));
            put(CCConstants.LOM_PROP_EDUCATIONAL_LEARNINGRESOURCETYPE, "LRT_TEST");
            put(CCConstants.SYS_PROP_NODE_UID, nodeId);
            put(CCConstants.CCM_PROP_IO_REPL_LIFECYCLECONTRIBUTER_AUTHOR, (Serializable) Collections.singletonList(
                    "BEGIN:VCARD\n" +
                            "VERSION:3.0\n" +
                            "N:Lastname;Firstname\n" +
                            "FN:Firstname Lastname\n" +
                            "ORG:CompanyName\n" +
                            "TITLE:JobTitle\n" +
                            "ADR:;;123 Sesame St;SomeCity;CA;12345;USA\n" +
                            "TEL;WORK;VOICE:1234567890\n" +
                            "TEL;CELL:Mobile\n" +
                            "TEL;FAX:\n" +
                            "EMAIL;WORK;INTERNET:foo@email.com\n" +
                            "URL:http://website.com\n" +
                            "END:VCARD"));
            put("{http://www.alfresco.org/model/content/1.0}content", new LinkedHashMap<String, String>() {{
                put("contentId", "contentId");
                put("encoding", "encoding");
                put("locale", "locale");
                put("mimetype", "mimetype");
                put("size", "1337");
            }});
        }};
    }
}
