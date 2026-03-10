package org.edu_sharing.elasticsearch.edu_sharing.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.jakarta.rs.json.JacksonJsonProvider;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.GsonBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.edu_sharing.elasticsearch.alfresco.client.NodeData;
import org.edu_sharing.elasticsearch.alfresco.client.NodeMetadata;
import org.edu_sharing.elasticsearch.alfresco.client.NodePreview;
import org.edu_sharing.elasticsearch.tools.Tools;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.Node;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.NodeEntry;
import org.edu_sharing.repository.client.tools.CCConstants;
import org.glassfish.jersey.logging.LoggingFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.threeten.bp.OffsetDateTime;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Slf4j
@Component
public class EduSharingClient {

    @Value("${alfresco.host}")
    String alfrescoHost;

    @Value("${alfresco.port}")
    String alfrescoPort;

    @Value("${alfresco.protocol}")
    String alfrescoProtocol;

    @Value("${alfresco.username}")
    String alfrescoUsername;

    @Value("${alfresco.password}")
    String alfrescoPassword;

    @Value("${log.requests}")
    String logRequests;


    Map<String, Set<String>> valuespaceProps = new HashMap<>();

    @Value("${valuespace.languages}")
    String[] valuespaceLanguages;

    @Value("${valuespace.cache.check.after.ms:120000}")
    long valuespaceCacheCheckAfterMs = 120000;

    @Value("${tracker.fetchThumbnails}")
    boolean fetchThumbnails;

    long valuespaceCacheLastChecked = -1;

    long valuespaceCacheLastModified = -1;

    @Value("${preview.maxKiloBytes : 100}")
    long previewMaxKiloBytes;


    private Client educlient;

    private String authorizationHeader;

    String URL_MDS_VALUES = "/edu-sharing/rest/mds/v1/metadatasets/-home-/${mds}/values";

    String URL_PREVIEW = "/edu-sharing/preview?nodeId=${nodeId}&storeProtocol=${storeProtocol}&storeId=${storeId}&crop=true&maxWidth=${width}&maxHeight=${height}&quality=${quality}";

    String URL_MDS = "/edu-sharing/rest/mds/v1/metadatasets/-home-/${mds}";
    String URL_NODE = "/edu-sharing/rest/node/v1/nodes/-home-/${node}/metadata";

    String URL_MDS_ALL = "/edu-sharing/rest/mds/v1/metadatasets/-home-";

    String URL_ABOUT = "/edu-sharing/rest/_about";

    String URL_REPOSITORIES = "/edu-sharing/rest/network/v1/repositories";

    String URL_STATISTICS_ALTERED = "/edu-sharing/rest/statistic/v1/statistics/nodes/altered";

    String URL_STATISTICS_NODE = "/edu-sharing/rest/statistic/v1/statistics/nodes/node";

    String URL_VALIDATE_SESSION = "/edu-sharing/rest/authentication/v1/validateSession";

    String URL_GET_USER = "/edu-sharing/rest/iam/v1/people/-home-/${user}";

    NewCookie jsessionId = null;

    Map<String, Map<String, Map<String, ValuespaceEntries>>> cache = new HashMap<>();

    @PostConstruct
    public void init() throws IOException {
        authorizationHeader = "Basic "
                + org.apache.cxf.common.util.Base64Utility.encode(String.format("%s:%s", alfrescoUsername, alfrescoPassword).getBytes());

        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        JacksonJsonProvider provider = new JacksonJsonProvider();
        provider.setMapper(mapper);

        educlient = ClientBuilder.newBuilder()
                .register(provider)
                .register(PreviewDataReader.class)
                .build();

        if (Boolean.parseBoolean(logRequests)) {
            educlient.register(new LoggingFeature());
        }
        //educlient.property("use.async.http.conduit", Boolean.TRUE);
        //educlient.property("org.apache.cxf.transport.http.async.usePolicy", AsyncHTTPConduitFactory.UseAsyncPolicy.ALWAYS);
        // relevant for external previews or static previews (e.g. svg)
        educlient.property("http.autoredirect", true);
        educlient.property("http.redirect.relative.uri", true);

        manageAuthentication();

        MetadataSets metadataSets = getMetadataSets();
        for (MetadataSet metadataSet : metadataSets.getMetadatasets()) {
            Set<String> valueSpacePropsTmp = new HashSet<>(getValuespaceProperties(metadataSet.getId()));
            valuespaceProps.put(metadataSet.getId(), valueSpacePropsTmp);
            log.info("added {} i18n props for mds: {}", valueSpacePropsTmp.size(), metadataSet.getId());
        }
    }

    public String translate(String mds, String language, String property, String key) {
        ValuespaceEntries entries = getValuespace(mds, language, property);
        if (entries.getError() != null) {
            log.warn("error while resolving valuespace entries mds:{} language:{} property:{} key:{} error message:{} m:{}", mds, language, property, key, entries.getError(), entries.getMessage());
            return null;
        }
        String result = null;
        for (ValuespaceEntry entry : entries.getValues()) {
            if (entry.getKey().equals(key)) {
                result = entry.getDisplayString();
            }
        }
        return result;
    }

    @EduSharingAuthentication.ManageAuthentication
    public void translateValuespaceProps(NodeData data) {

        Map<String, Serializable> properties = data.getNodeMetadata().getProperties();

        String mds = getMdsId(data);

        Set<String> valueSpacePropsMds = getPropsMdsList(mds);
        if (valueSpacePropsMds == null) {
            log.warn("no i18n props found for mds:{}", mds);
            return;
        }

        for (Map.Entry<String, Serializable> prop : properties.entrySet()) {
            translateProperty(data, mds, valueSpacePropsMds, prop);
        }


    }

    private Set<String> getPropsMdsList(String mds) {
        return valuespaceProps.get(mds);
    }

    public String getMdsId(NodeData data) {
        String mds = (String) data.getNodeMetadata().getProperties().get(CCConstants.CM_PROP_METADATASET_EDU_METADATASET);
        if (mds == null) {
            mds = "default";
        }

        if (mds.equals("default")) {
            //"default" in repo is hard coded, should map on the first registered mds in repo
            mds = valuespaceProps.keySet().iterator().next();
        }
        return mds;
    }

    public void translateProperty(NodeData data, String mds, Set<String> valueSpacePropsMds, Map.Entry<String, Serializable> prop) {
        if (valueSpacePropsMds == null) {
            valueSpacePropsMds = getPropsMdsList(mds);
        }
        String key = CCConstants.getValidLocalName(prop.getKey());
        if (key == null) {
            key = prop.getKey();
        }


        if (valueSpacePropsMds.contains(key)) {
            for (String language : valuespaceLanguages) {
                Serializable translated = null;

                if (prop.getValue() == null) continue;

                if (prop.getValue() instanceof List) {
                    ArrayList<String> translatedList = new ArrayList<>();
                    for (Serializable value : (List<Serializable>) prop.getValue()) {
                        if (value instanceof String) {
                            String translatedVal = translate(mds, language, key, (String) value);
                            if (StringUtils.isNotBlank(translatedVal)) {
                                translatedList.add(translatedVal);
                            }
                        } else {
                            log.warn("Can't translate value for field {} of type {} at node {}", key, value.getClass(), data.getNodeMetadata().getNodeRef());
                        }
                    }
                    if (!translatedList.isEmpty()) {
                        translated = translatedList;
                    }
                } else {
                    String translatedVal = translate(mds, language, key, prop.getValue().toString());
                    if (translatedVal != null) {
                        translated = translatedVal;
                    }
                }

                Map<String, List<String>> valuespacesForLanguage = data.getValueSpaces()
                        .computeIfAbsent(language, k -> new ConcurrentHashMap<>());
                if (translated instanceof List) {
                    valuespacesForLanguage.put(prop.getKey(), (List<String>) translated);
                } else {
                    valuespacesForLanguage.put(prop.getKey(), Collections.singletonList((String) translated));
                }
            }
        }
    }

    /**
     * Retrieves the valuespace entries for the specified metadata set, language, and property.
     * If the entries are available in the cache, they are returned directly.
     * Otherwise, the method requests the data from a remote service, updates the cache, and returns the result.
     *
     * @param mds      The metadata set identifier for which the valuespace entries are requested.
     * @param language The language for which the valuespace entries are requested.
     * @param property The specific property within the metadata set for which the valuespace entries are requested.
     * @return The {@link ValuespaceEntries} object representing the retrieved valuespace data.
     */
    public ValuespaceEntries getValuespace(String mds, String language, String property) {

        ValuespaceEntries entries = getValuespaceFromCache(mds, language, property);

        if (entries != null) {
            log.debug("got valuespace entries from cache");
            return entries;
        }

        GetValuesParameters params = new GetValuesParameters();
        GetValuesParameters.ValueParameters vp = new GetValuesParameters.ValueParameters();

        String url = URL_MDS_VALUES;
        url = url.replace("${mds}", mds);
        url = getUrl(url);

        vp.setProperty(property);
        vp.setQuery("ngsearch");
        params.setValueParameters(vp);

        // TODO Autocloseable
        entries = educlient
                .target(url)
                .request(MediaType.APPLICATION_JSON)
                .header("locale", language)
                .cookie(jsessionId.getName(), jsessionId.getValue())
                .post(Entity.json(params)).readEntity(ValuespaceEntries.class);
        addValuespaceToCache(mds, language, property, entries);
        return entries;
    }

    @EduSharingAuthentication.ManageAuthentication
    public List<String> getValuespaceProperties(String mds) {
        String url = URL_MDS;
        url = url.replace("${mds}", mds);
        url = getUrl(url);
        MdsV2 mdsV2 = educlient
                .target(url)
                .request(MediaType.APPLICATION_JSON)
                .cookie(jsessionId.getName(), jsessionId.getValue())
                .get().readEntity(MdsV2.class);

        List<String> result = new ArrayList<>();
        for (WidgetV2 widget : mdsV2.getWidgets()) {
            if (widget.isHasValues()) {
                result.add(widget.getId());
            }
        }
        return result;
    }

    private Response getPreviewDataResponse(String url) {
        return educlient.target(url).
                request(MediaType.WILDCARD).
                cookie(jsessionId.getName(), jsessionId.getValue()).
                get();
    }

    public NodePreview getNodePreview(String nodeRef){
        NodePreview preview = new NodePreview();

        String url = getUrl(URL_PREVIEW).
                replace("${nodeId}", Tools.getUUID(nodeRef)).
                replace("${storeProtocol}", Tools.getProtocol(nodeRef)).
                replace("${storeId}", Tools.getIdentifier(nodeRef));

        url += "&allowRedirect=false";

        String urlSmall = url.replace("${width}", "400").
                replace("${height}", "400").
                replace("${quality}", "60");

        try {
            Response previewDataResponse = getPreviewDataResponse(urlSmall);
            String isIcon = previewDataResponse.getHeaderString("X-Edu-IsIcon");
            if(isIcon == null){
                // when redirect no headers are set
                preview.setIsIcon(true);
                preview.setType("TYPE_DEFAULT");
            }else {
                preview.setIsIcon(Boolean.parseBoolean(isIcon));
            }
            String type = previewDataResponse.getHeaderString("X-Edu-PreviewType");
            preview.setType(type);
            PreviewData previewSmall = previewDataResponse.readEntity(PreviewData.class);
            if (previewSmall != null && !preview.isIcon()) {
                if (previewSmall.getData() != null && (previewSmall.getData().length / 1024) > previewMaxKiloBytes) {
                    log.info("Skipping preview for {} cause size {}kb exceeds limit {}kb", nodeRef, previewSmall.getData().length / 1024, previewMaxKiloBytes);
                    return null;
                }
                preview.setMimetype(previewSmall.getMimetype());
                preview.setSmall(previewSmall.getData());
            }
            return preview;
        }catch (Exception e){
            log.info("Could not fetch preview from {}", url, e);
            return null;
        }
    }


    private NodeEntry getNode(String nodeId) {
        log.debug("calling getNode");
        String result = educlient.target(getUrl(URL_NODE.replace("${node}", nodeId))).
                request(MediaType.APPLICATION_JSON).
                accept(MediaType.APPLICATION_JSON).
                cookie(jsessionId.getName(), jsessionId.getValue()).
                get().readEntity(String.class);
        log.debug(result);
        return new GsonBuilder().setExclusionStrategies(new ExclusionStrategy() {
            @Override
            public boolean shouldSkipField(FieldAttributes fieldAttributes) {
                return false;
            }

            @Override
            public boolean shouldSkipClass(Class<?> aClass) {
                return aClass.equals(OffsetDateTime.class)
                        || aClass.equals(java.time.OffsetDateTime.class);
            }
        }).create().fromJson(result, NodeEntry.class);
    }

    public About getAbout() {
        String url = URL_ABOUT;
        url = getUrl(url);
        try {
            return educlient
                    .target(url)
                    .request(MediaType.APPLICATION_JSON)
                    .get().readEntity(About.class);
        } catch (ProcessingException e) {
            log.error("Error while trying to fetch edu-sharing API at {}. Make sure you're running edu-sharing >= 6.0", url, e);
            throw e;
        }
    }

    public ValidateSessionResponse validateSession() {
        log.debug("edu-sharing validateSession");
        String url = URL_VALIDATE_SESSION;
        url = getUrl(url);
        return educlient.
                target(url).
                request(MediaType.APPLICATION_JSON).
                accept(MediaType.APPLICATION_JSON).
                cookie(jsessionId.getName(), jsessionId.getValue()).
                get().readEntity(ValidateSessionResponse.class);
    }

    public void authenticate() {
        log.info("edu-sharing authentication");

        //auto redirect leads to endless loop when auth fails, tempory deactivate
        educlient.property("http.autoredirect", false);
        educlient.property("http.redirect.relative.uri", false);
        try {
            String url = URL_GET_USER;
            url = url.replace("${user}", alfrescoUsername);
            url = getUrl(url);
            Response response = educlient.target(url)
                    .request(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                    .accept(MediaType.APPLICATION_JSON).
                    get();

            if (response.getStatus() != 200) {
                String message = "edu-sharing authentication failed:" + response.getStatus();
                log.error(message);
                throw new RuntimeException(message);
            }
            jsessionId = response.getCookies().get("JSESSIONID");
            // does not work for secure cookies
            if (jsessionId == null) {
                Pattern cookiePattern = Pattern.compile("JSESSIONID=(.*); Path");
                Matcher m = cookiePattern.matcher(response.getHeaderString("Set-Cookie"));
                if (!m.find()) {
                    throw new RuntimeException("No cookie received from edu-sharing server " + alfrescoHost);
                }
                jsessionId = new NewCookie.Builder("JSESSIONID")
                        .value(m.group(1))
                        .build();
            }

        } finally {
            educlient.property("http.autoredirect", true);
            educlient.property("http.redirect.relative.uri", true);
        }

    }

    public void manageAuthentication() {
        ValidateSessionResponse validateSessionResponse = null;
        if (jsessionId != null) {
            validateSessionResponse = this.validateSession();
        }
        if (validateSessionResponse == null || !"OK".equals(validateSessionResponse.getStatusCode())) {
            log.info("have to refresh edu-sharing cookie");
            boolean authFinished = false;
            while (!authFinished) {
                try {
                    authenticate();
                    authFinished = true;
                } catch (Exception e) {
                    log.warn("auth failed cause of: {} retrying in ms: {}", Tools.WAIT_FOR_SERVICE, e.getMessage());
                    try {
                        Thread.sleep(Tools.WAIT_FOR_SERVICE);
                    } catch (InterruptedException ex) {
                        log.error(ex.getMessage(), ex);
                    }
                }
            }

        }
    }

    @EduSharingAuthentication.ManageAuthentication
    public MetadataSets getMetadataSets() {
        String url = URL_MDS_ALL;
        url = getUrl(url);
        return educlient.target(url)
                .request(MediaType.APPLICATION_JSON)
                .cookie(jsessionId.getName(), jsessionId.getValue())
                .accept(MediaType.APPLICATION_JSON)
                .get()
                .readEntity(MetadataSets.class);
    }

    @EduSharingAuthentication.ManageAuthentication
    public Repository getHomeRepository() {
        String url = URL_REPOSITORIES;
        url = getUrl(url);
        Repositories repositories = educlient.target(url)
                .request(MediaType.APPLICATION_JSON)
                .cookie(jsessionId.getName(), jsessionId.getValue())
                .get()
                .readEntity(Repositories.class);
        for (Repository rep : repositories.getRepositories()) {
            if (rep.isHomeRepo()) return rep;
        }
        return null;
    }

    @EduSharingAuthentication.ManageAuthentication
    public List<String> getStatisticsNodeIds(long tsFrom, long tsTo) {
        String url = URL_STATISTICS_ALTERED;
        url = getUrl(url);

        return educlient.target(url).
                queryParam("dateFrom", tsFrom)
                .queryParam("dateTo", tsTo)
                .request(MediaType.APPLICATION_JSON)
                .cookie(jsessionId.getName(), jsessionId.getValue())
                .get()
                .readEntity(List.class);
    }


    @EduSharingAuthentication.ManageAuthentication
    public List<NodeStatistic> getStatisticsForNode(String nodeId, long timestamp) {
        String url = URL_STATISTICS_NODE;
        url = getUrl(url);

        return educlient.target(url).
                path(nodeId).
                queryParam("dateFrom", timestamp).
                request(MediaType.APPLICATION_JSON).
                cookie(jsessionId.getName(), jsessionId.getValue()).
                get().readEntity(new GenericType<List<NodeStatistic>>() {
                });
    }


    /**
     * refreshes cache when necessary
     * use valuespace.cache.check.after.ms config to determine check frequence
     */
    public void refreshValuespaceCache() {
        if (valuespaceCacheLastChecked == -1
                || valuespaceCacheLastChecked < (System.currentTimeMillis() - valuespaceCacheCheckAfterMs)) {
            log.info("will check if cache in edu-sharing changed");
            About about = getAbout();
            if (about.getLastCacheUpdate() > valuespaceCacheLastModified) {
                log.info("repos last cache updated{}: force valuespace cache refresh", new Date(about.getLastCacheUpdate()));
                cache.clear();
                valuespaceCacheLastModified = about.getLastCacheUpdate();
            }
            valuespaceCacheLastChecked = System.currentTimeMillis();
        }

    }

    private String getUrl(String path) {
        return alfrescoProtocol + "://" + alfrescoHost + ":" + alfrescoPort + path;
    }

    private ValuespaceEntries getValuespaceFromCache(String mds, String language, String property) {

        Map<String, Map<String, ValuespaceEntries>> mdsMap = cache.get(mds);
        if (mdsMap == null) {
            return null;
        }

        Map<String, ValuespaceEntries> propMap = mdsMap.get(language);
        if (propMap == null) {
            return null;
        }
        return propMap.get(property);
    }

    private void addValuespaceToCache(String mds, String language, String property, ValuespaceEntries entries) {
        Map<String, Map<String, ValuespaceEntries>> mdsMap = cache.computeIfAbsent(mds, k -> new HashMap<>());
        Map<String, ValuespaceEntries> propMap = mdsMap.computeIfAbsent(language, k -> new HashMap<>());
        propMap.put(property, entries);
    }

}
