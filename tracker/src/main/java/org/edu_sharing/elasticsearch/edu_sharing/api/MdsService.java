package org.edu_sharing.elasticsearch.edu_sharing.api;

import lombok.RequiredArgsConstructor;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.Mds;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.MdsIndex;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.MdsWidget;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.Suggestions;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MdsService {

    private final MdsCachedApi mdsCachedApi;

    @Cacheable(cacheNames = "mdsValueSpaceProbs", key = "#mdsId", cacheManager = "mdsCacheManager")
    public Set<String> getValueSpaceProbertyIds(String mdsId) {
        Mds metadataSet = mdsCachedApi.getMetadataSet(mdsId);
        return metadataSet.getWidgets()
                .stream()
                .filter(Objects::nonNull)
                .filter(x -> Boolean.TRUE.equals(x.getHasValues()))
                .map(MdsWidget::getId)
                .collect(Collectors.toSet());
    }

    @Cacheable(cacheNames = "mdsSuggestProps", key = "#mdsId", cacheManager = "mdsCacheManager")
    public Set<String> getSuggestPropertyIds(String mdsId) {
        Mds metadataSet = mdsCachedApi.getMetadataSet(mdsId);
        return metadataSet.getWidgets()
                .stream()
                .filter(Objects::nonNull)
                .filter(x -> x.getSuggestionSource() != null && x.getSuggestionSource().equals("Sql"))
                .map(MdsWidget::getId)
                .collect(Collectors.toSet());
    }

    @Cacheable(cacheNames = "mdsJsonDataPropertyIds", key = "#mdsId", cacheManager = "mdsCacheManager")
    public Set<String> getJsonDataPropertyIds(String mdsId) {
        Mds metadataSet = mdsCachedApi.getMetadataSet(mdsId);
        return metadataSet.getWidgets()
                .stream()
                .filter(Objects::nonNull)
                .filter(x -> x.getIndex() != null)
                .filter(x -> x.getIndex().getDataType() == MdsIndex.DataTypeEnum.JSON_DATA)
                .map(MdsWidget::getId)
                .collect(Collectors.toSet());
    }

    public Suggestions getValuespace(String mds, String language, String property) {
        return mdsCachedApi.getValuespace(mds, language, property);
    }

    public Mds getMetadataSet(String mdsId) {
        return mdsCachedApi.getMetadataSet(mdsId);
    }
}

