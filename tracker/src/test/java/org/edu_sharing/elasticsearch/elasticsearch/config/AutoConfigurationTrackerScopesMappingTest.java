package org.edu_sharing.elasticsearch.elasticsearch.config;

import co.elastic.clients.elasticsearch._types.mapping.DynamicMapping;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import org.edu_sharing.elasticsearch.elasticsearch.core.migration.MigrationInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AutoConfigurationTrackerScopesMappingTest {

    static Stream<Arguments> unlimitedKeywordFields() {
        return Stream.of(Arguments.of((List<String>) null), Arguments.of(List.of("ccm:wwwurl")));
    }

    private TypeMapping mapping(List<String> unlimitedKeywordFields) {
        return new AutoConfigurationTracker(List.of(MigrationInfo.builder().version("test").build()))
                .getWorkspaceMappings(new TypeMapping.Builder(), unlimitedKeywordFields)
                .build();
    }

    @ParameterizedTest
    @MethodSource("unlimitedKeywordFields")
    void scopeFieldsAreMapped(List<String> unlimitedKeywordFields) {
        TypeMapping mapping = mapping(unlimitedKeywordFields);

        for (String field : List.of("scope_ids", "scope_ids_published", "scope_ids_excluded", "scope_overrides")) {
            assertTrue(mapping.properties().get(field).isKeyword(), field);
        }

        Property scopes = mapping.properties().get("scopes");
        assertTrue(scopes.isNested());
        assertTrue(scopes.nested().properties().get("scopeId").isKeyword());
        assertTrue(scopes.nested().properties().get("published").isBoolean());
        assertTrue(scopes.nested().properties().get("excluded").isBoolean());
        assertTrue(scopes.nested().properties().get("created").isDate());
        assertTrue(scopes.nested().properties().get("modified").isDate());
        assertTrue(scopes.nested().properties().get("createdBy").isKeyword());
        assertTrue(scopes.nested().properties().get("modifiedBy").isKeyword());
        assertTrue(scopes.nested().properties().get("publicFields").isKeyword());
        Property properties = scopes.nested().properties().get("properties");
        assertTrue(properties.isObject());
        assertEquals(DynamicMapping.True, properties.object().dynamic());
    }

    /**
     * the first matching dynamic template wins. The generic "*properties.*" templates (e.g. title_type) copy values
     * into properties_aggregated, which must never happen for scope values (replaced values only inside the own scope)
     */
    @ParameterizedTest
    @MethodSource("unlimitedKeywordFields")
    void scopePropertiesTemplateIsFirstAndHasNoCopyTo(List<String> unlimitedKeywordFields) {
        TypeMapping mapping = mapping(unlimitedKeywordFields);

        var first = mapping.dynamicTemplates().get(0);
        assertEquals("scopes_properties_type", first.name());
        var template = first.value();
        assertEquals("scopes.properties.*", template.pathMatch().get(0));
        Property property = template.mapping();
        assertTrue(property.isText());
        assertTrue(property.text().copyTo().isEmpty());
        assertTrue(property.text().fields().containsKey("keyword"));
        assertTrue(property.text().fields().containsKey("sort"));
    }
}
