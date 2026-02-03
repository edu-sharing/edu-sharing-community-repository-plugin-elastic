package org.edu_sharing.elasticsearch.elasticsearch.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

public class DataBuilderSerializer {

    /**
     * Serializes the given data object into a format compatible with
     * the {@code DataBuilder}, facilitating structured data construction.
     *
     * @param dataBuilder the {@code DataBuilder} instance used to build the serialized data structure;
     *                    must not be {@code null}.
     * @param data        the object to serialize; must not be {@code null}.
     */
    public static void serialize(@NotNull @NonNull DataBuilder dataBuilder, @NotNull @NonNull Object data) {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.valueToTree(data);
        writeNode(dataBuilder, jsonNode);
    }

    private static void writeNode(DataBuilder dataBuilder, JsonNode node) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String fieldName = entry.getKey();
                JsonNode fieldValue = entry.getValue();

                if (fieldValue.isObject()) {
                    dataBuilder.startObject(fieldName);
                    writeNode(dataBuilder, fieldValue);
                    dataBuilder.endObject();
                } else if (fieldValue.isArray()) {
                    dataBuilder.startArray(fieldName);
                    fieldValue.forEach(arrayElement -> writeNode(dataBuilder, arrayElement));
                    dataBuilder.endArray();
                } else {
                    writeValue(dataBuilder, fieldName, fieldValue);
                }
            });
        } else if (node.isArray()) {
            node.forEach(arrayElement -> writeNode(dataBuilder, arrayElement));
        } else {
            writeValue(dataBuilder, null, node);
        }
    }

    private static void writeValue(DataBuilder dataBuilder, String fieldName, JsonNode value) {
        if (value.isNull()) {
            if (fieldName == null) {
                dataBuilder.nullValue();
            }
        } else if (value.isBoolean()) {
            if (fieldName != null) {
                dataBuilder.field(fieldName, value.asBoolean());
            } else {
                dataBuilder.value(value.asBoolean());
            }
        } else if (value.isNumber()) {
            if (fieldName != null) {
                if (value.isIntegralNumber()) {
                    dataBuilder.field(fieldName, value.asLong());
                } else {
                    dataBuilder.field(fieldName, value.asDouble());
                }
            } else {
                if (value.isIntegralNumber()) {
                    dataBuilder.value(value.asLong());
                } else {
                    dataBuilder.value(value.asDouble());
                }
            }
        } else {
            if (fieldName != null) {
                dataBuilder.field(fieldName, value.asText());
            } else {
                dataBuilder.value(value.asText());
            }
        }
    }
}
