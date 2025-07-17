package org.edu_sharing.elasticsearch.edu_sharing.api.preview;

import org.jetbrains.annotations.NotNull;
import org.reactivestreams.Publisher;
import org.springframework.core.ResolvableType;
import org.springframework.core.codec.Decoder;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PreviewDataDecoder implements Decoder<PreviewData> {

    @Override
    public boolean canDecode(ResolvableType elementType, MimeType mimeType) {
        return elementType.toClass() == PreviewData.class;
    }

    @NotNull
    @Override
    public Flux<PreviewData> decode(@NotNull Publisher<DataBuffer> inputStream, @NotNull ResolvableType elementType,
                                    MimeType mimeType, Map<String, Object> hints) {
        // Optional: falls Stream-Parsing benötigt wird
        throw new UnsupportedOperationException("Stream decoding not supported");
    }

    @NotNull
    @Override
    public Mono<PreviewData> decodeToMono(@NotNull Publisher<DataBuffer> inputStream, @NotNull ResolvableType elementType,
                                          MimeType mimeType, Map<String, Object> hints) {
        return DataBufferUtils.join(inputStream)
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    PreviewData previewData = new PreviewData();
                    previewData.setMimetype(mimeType.toString());
                    previewData.setData(bytes);
                    return previewData;
                });
    }


    @NotNull
    @Override
    public List<MimeType> getDecodableMimeTypes() {
        return Collections.emptyList();
    }
}
