package org.edu_sharing.elasticsearch.alfresco.client;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AlfrescoWebscriptClientObjectMapperTest {

    private final JsonMapper objectMapper = new AlfrescoWebscriptClient(null).buildObjectMapper();

    @Test
    void deserializesNodeMetadatasIgnoringUnknownTopLevelField() throws Exception {
        // Arrange
        // Alfresco occasionally adds a "status" field that NodeMetadatas does not declare.
        String json = "{\"nodes\":[{}],\"status\":{\"code\":500,\"name\":\"INTERNAL_SERVER_ERROR\"}}";

        // Act
        NodeMetadatas result = objectMapper.readValue(json, NodeMetadatas.class);

        // Assert
        assertThat(result.getNodes()).hasSize(1);
    }

    @Test
    void doesNotThrowOnUnknownNestedField() {
        // Arrange
        String json = "{\"nodes\":[{\"unexpectedNested\":\"x\"}],\"status\":\"error\"}";

        // Act / Assert
        assertThatCode(() -> objectMapper.readValue(json, NodeMetadatas.class))
                .doesNotThrowAnyException();
    }
}
