package org.edu_sharing.elasticsearch.edu_sharing.api.preview;

import lombok.RequiredArgsConstructor;
import org.springframework.web.reactive.function.client.WebClient;

@RequiredArgsConstructor
public class PreviewApi {

    private final WebClient webClient;

    public PreviewData getPreviewData(String storeProtocol, String storeId, String nodeId, int maxWidth, int maxHeight, int quality) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/preview")
                        .queryParam("nodeId", nodeId)
                        .queryParam("storeProtocol", storeProtocol)
                        .queryParam("storeId", storeId)
                        .queryParam("crop", true)
                        .queryParam("maxWidth", maxWidth)
                        .queryParam("maxHeight", maxHeight)
                        .queryParam("quality", quality)
                        .queryParam("allowRedirect", false)
                        .build()
                )
                .header("Accept", "*/*")

                .retrieve().bodyToMono(PreviewData.class).block();
    }

    ;

}
