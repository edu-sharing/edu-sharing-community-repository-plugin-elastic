package org.edu_sharing.elasticsearch.edu_sharing.api.scope;

import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;

/**
 * Client for the tracker endpoints of the repository scope api (/rest/scope/v1). Written by hand like
 * {@link org.edu_sharing.elasticsearch.edu_sharing.api.preview.PreviewApi}, it does not depend on the generated client.
 */
@RequiredArgsConstructor
public class ScopeApi {

    private static final String BASE_PATH = "/rest/scope/v1/-home-";

    private final WebClient webClient;

    /**
     * change feed ordered by (at, seq), same cursor contract as the share info oplog
     */
    public List<ScopeChangeData> getChanges(OffsetDateTime after, Long afterSeq, OffsetDateTime until, int maxItems) {
        return webClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path(BASE_PATH + "/changes").queryParam("maxItems", maxItems);
                    if (after != null) {
                        uriBuilder.queryParam("after", after.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    }
                    if (afterSeq != null) {
                        uriBuilder.queryParam("afterSeq", afterSeq);
                    }
                    if (until != null) {
                        uriBuilder.queryParam("until", until.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                    }
                    return uriBuilder.build();
                })
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<ScopeChangeData>>() {
                })
                .block();
    }

    /**
     * scope fields of the nodes. Child objects get the fields of their parent
     */
    public List<ScopeIndexFragment> getIndexData(Collection<String> nodeIds) {
        return webClient.post()
                .uri(BASE_PATH + "/index")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(nodeIds)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<ScopeIndexFragment>>() {
                })
                .block();
    }
}
