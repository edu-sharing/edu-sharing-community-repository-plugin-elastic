package org.edu_sharing.elasticsearch.edu_sharing.client;

import org.springframework.util.StreamUtils;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

public class PreviewDataReader implements MessageBodyReader<PreviewData> {

    @Override
    public boolean isReadable(Class<?> aClass, Type type, Annotation[] annotations, MediaType mediaType) {
        return true;
    }

    @Override
    public PreviewData readFrom(Class<PreviewData> aClass, Type type, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> multivaluedMap, InputStream inputStream) throws IOException, WebApplicationException {
        PreviewData result = new PreviewData();
        result.setMimetype(mediaType.toString());
        result.setData(StreamUtils.copyToByteArray(inputStream));
        return result;
    }
}
