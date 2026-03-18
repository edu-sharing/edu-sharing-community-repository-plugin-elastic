package org.edu_sharing.elasticsearch.tools;

import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.cardme.engine.VCardEngine;
import net.sourceforge.cardme.vcard.VCard;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.edu_sharing.elasticsearch.alfresco.client.NodeData;
import org.edu_sharing.elasticsearch.edu_sharing.api.EduSharingService;
import org.edu_sharing.elasticsearch.elasticsearch.utils.DataBuilder;
import org.edu_sharing.elasticsearch.elasticsearch.core.WorkspaceService;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * this class checks for groovy scripts in the "scripts" resources folder and executes them
 * The return values of each script (must be a map) will be stored in the "customProperties" section of the index
 * If multiple scripts are present, they will be sorted by filename and run after each other
 * Each script will get information about the current node
 * Check the {@link #getBindings(Map)} method for a list of available attributes
 */
@Slf4j
@Service
public class ScriptExecutor {
    private final EduSharingService eduSharingService;
    private final ScriptLoaderConfiguration.ScriptLoaderService scriptLoaderService;
    private final GroovyClassLoader groovyClassLoader = new GroovyClassLoader();
    private final Map<File, Class<? extends Script>> scriptCache = new ConcurrentHashMap<>();


    public ScriptExecutor(EduSharingService eduSharingService, ScriptLoaderConfiguration.ScriptLoaderService scriptLoaderService) {
        this.eduSharingService = eduSharingService;
        this.scriptLoaderService = scriptLoaderService;
        init();
    }

    public void addCustomPropertiesByScript(DataBuilder builder, NodeData nodeData) {
        Map<String, Object> metadata = nodeData.getNodeMetadata().getProperties().entrySet().stream()
                .collect(HashMap::new, (m, v) -> m.put(CCConstants.getValidLocalName(v.getKey()), v.getValue()), HashMap::putAll);
        builder.startObject("customProperties");
        for (File script : scriptCache.keySet()) {
            try {

                Binding sharedData = getBindings(metadata, nodeData.getNodeMetadata().getAspects());
                Script groovy = getScript(script);
                groovy.setBinding(sharedData);

                Map<String, Serializable> result = (Map<String, Serializable>) groovy.run();
                if (result != null) {
                    String mds = eduSharingService.getMdsId(nodeData);
                    for (Map.Entry<String, Serializable> entry : result.entrySet()) {
                        String key = entry.getKey();
                        Serializable value = entry.getValue();
                        builder.field(key, value);
                        log.debug("Script: {}, key: {}, value: {}", script.getName(), key, value);
                        eduSharingService.translateProperty(nodeData, mds, new AbstractMap.SimpleEntry<>(
                                "customProperties." + entry.getKey(), entry.getValue()
                        ));
                    }

                }
            } catch (Throwable t) {
                log.warn("Could not execute script {}", script.getName(), t);
            }
        }
        builder.endObject();
    }

    private Binding getBindings(Map<String, Object> metadata, Collection<String> aspects) {
        Binding sharedData = new Binding();
        sharedData.setProperty("metadata", metadata);
        sharedData.setProperty("aspects", aspects);
        sharedData.setProperty("contributor", getContributor(metadata));
        return sharedData;
    }

    private Map<String, Set<VCard>> getContributor(Map<String, Object> metadata) {
        Map<String, Set<VCard>> result = new HashMap<>();
        Set<String> contributorProperties = metadata.keySet().stream().filter(key -> key != null && key.matches(WorkspaceService.CONTRIBUTOR_REGEX)).collect(Collectors.toSet());
        if (!contributorProperties.isEmpty()) {
            VCardEngine vcardEngine = new VCardEngine();
            contributorProperties.forEach(key -> {
                Object value = metadata.get(key);
                if (value instanceof List) {
                    List<String> mapped = (List<String>) value;
                    result.put(key, mapped.stream().filter(Objects::nonNull).map(v -> {
                        try {
                            return vcardEngine.parse(v);
                        } catch (Exception e) {
                            log.debug(e.getMessage(), e);
                            return null;
                        }
                    }).filter(Objects::nonNull).collect(Collectors.toSet()));
                }
            });
        }
        return result;
    }

    private void init(){
        try {
            File[] scripts = scriptLoaderService.getFiles();
            if (scripts == null) {
                scripts = new File[0];
            }
            Arrays.sort(scripts);

            for(File script : scripts) {
                scriptCache.put(script,groovyClassLoader.parseClass(script));
                log.info("Registered script: {}", script.getName());
            }
        } catch (Throwable t) {
            log.warn("Could not init scripts", t);
        }
    }

    Script getScript(File script) throws Exception {
        return scriptCache.get(script).getDeclaredConstructor().newInstance();
    }
}
