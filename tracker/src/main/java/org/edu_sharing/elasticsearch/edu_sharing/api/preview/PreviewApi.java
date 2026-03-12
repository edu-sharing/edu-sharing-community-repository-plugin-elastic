package org.edu_sharing.elasticsearch.edu_sharing.api.preview;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
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
                .exchangeToMono(response -> {
                    String isIcon = response.headers().asHttpHeaders().getFirst("X-Edu-IsIcon");
                    String type = response.headers().asHttpHeaders().getFirst("X-Edu-PreviewType");

                    if(isIcon == null && type == null){
                        // on PreviewServlet redirect no headers are set
                        isIcon = String.valueOf(true);
                        type = "TYPE_DEFAULT";
                    }

                    boolean finalIsIcon = Boolean.parseBoolean(isIcon);
                    String finalType = type;
                    return response.bodyToMono(PreviewData.class)
                            .map(previewData -> {
                                previewData.setIcon(finalIsIcon);
                                previewData.setType(finalType);
                                return previewData;
                            });
                })
                .doOnError(e -> log.error("Error fetching preview for nodeId: {}", nodeId, e))
                .block();
    }

    ;

}
