package org.edu_sharing.elasticsearch.edu_sharing.api;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.edu_sharing.generated.repository.backend.services.rest.client.api.MdsV1Api;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.Mds;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.SuggestionParam;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.Suggestions;
import org.edu_sharing.generated.repository.backend.services.rest.client.model.ValueParameters;
import org.jetbrains.annotations.NotNull;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class MdsCachedApi {
    private final MdsV1Api mdsV1Api;

    @Cacheable(key = "#mds", cacheNames = "mdsCache", sync = true, cacheManager = "mdsCacheManager")
    public Mds getMetadataSet(@NotNull @NonNull String mds){
        return mdsV1Api.getMetadataSet(EduSharingService.DEFAULT_REPOSITORY, mds)
                .block();
    }

    @Cacheable(key = "#mds-#language-#property", cacheNames = "mdsValuespace", sync = true, cacheManager = "mdsCacheManager")
    public Suggestions getValuespace(String mds, String language, String property) {
        SuggestionParam suggestionParam = SuggestionParam.builder()
                .valueParameters(ValueParameters.builder()
                        .query("ngsearch")
                        .property(property)
                        .build())
                .build();

        return mdsV1Api.getValues(EduSharingService.DEFAULT_REPOSITORY, mds, language, suggestionParam).block();
    }
}