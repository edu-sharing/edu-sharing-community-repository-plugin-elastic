package org.edu_sharing.elasticsearch.elasticsearch.core.types;

import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Configuration class for managing types used in the application. It is annotated as a Spring
 * The types are stored as a map where the key is a type identifier, and the value is a {@link TypesConfigItem}.
 */
@Component
@ConfigurationProperties(prefix = "app")
public class TypesConfig {
    private Map<String, TypesConfigItem> types;

    public void setTypes(Map<String, TypesConfigItem> types) {
        this.types = types.values().stream().collect(Collectors.toMap(TypesConfigItem::type, x->x));
    }

    /**
     * Retrieves the configuration for the specified type. If the type does not exist in the configuration,
     * a new {@link TypesConfigItem} is created with default values (does nothing) and added to the configuration map.
     *
     * @param type the key identifying the type for which the configuration is retrieved; must not be null
     * @return the {@link TypesConfigItem} corresponding to the specified type; never null
     */
    @NotNull
    public TypesConfigItem getTypeConfig(@NotNull String type) {
        return types.computeIfAbsent(type, t -> new TypesConfigItem(t, false, Collections.emptyList(), new ReindexParentConfig(false, 0, new FilterConfig(Collections.emptyList()))));
    }
}
