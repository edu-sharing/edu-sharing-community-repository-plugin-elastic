package org.edu_sharing.elasticsearch.rag.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.netty.resolver.DefaultAddressResolverGroup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Exceptions;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.util.ArrayList;
import java.util.List;

/**
 * Embedding client speaking the OpenAI {@code /v1/embeddings} dialect.
 * <p>
 * One implementation covers every backend worth considering: HuggingFace TEI, Ollama and vLLM all
 * serve this shape, as do the hosted providers. That is the whole reason the concept settled on this
 * protocol rather than TEI's native {@code /embed} - swapping the backend becomes a URL change.
 * <p>
 * Not a Spring bean. Instances are created per embedding profile, so the profile registrar builds
 * them; the same reason {@code ChunkingService} carries no annotation either.
 */
@Slf4j
public class OpenAiCompatibleEmbeddingClient implements EmbeddingService {

    /**
     * A batch of 32 vectors at 1024 dimensions is roughly 400 KB of JSON, comfortably past
     * WebClient's 256 KB default. Left at the default, every response would fail with a buffer
     * overflow that reads like a network problem.
     */
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;

    private final WebClient webClient;
    private final EmbeddingProperties properties;

    public OpenAiCompatibleEmbeddingClient(EmbeddingProperties properties) {
        this(properties, defaultWebClient(properties));
    }

    OpenAiCompatibleEmbeddingClient(EmbeddingProperties properties, WebClient webClient) {
        this.properties = properties;
        this.webClient = webClient;
    }

    @Override
    public int dimensions() {
        return properties.dimensions();
    }

    @Override
    public String model() {
        return properties.model();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        // Sequential on purpose: the tracker already runs several node partitions in parallel, and
        // stacking a second layer of concurrency here would only overrun the inference service.
        for (int from = 0; from < texts.size(); from += properties.batchSize()) {
            int to = Math.min(from + properties.batchSize(), texts.size());
            vectors.addAll(embedBatch(texts.subList(from, to)));
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> batch) {
        EmbeddingResponse response;
        try {
            response = webClient.post()
                    .uri("/v1/embeddings")
                    .headers(this::authorize)
                    .bodyValue(new EmbeddingRequest(properties.model(), batch))
                    .retrieve()
                    .bodyToMono(EmbeddingResponse.class)
                    .retryWhen(Retry
                            .backoff(properties.maxRetries(), properties.retryDelay())
                            .filter(OpenAiCompatibleEmbeddingClient::isTransient)
                            .doBeforeRetry(signal -> log.warn("retrying embedding request ({}): {}",
                                    signal.totalRetries() + 1, signal.failure().toString())))
                    .block();
        } catch (Exception e) {
            // Reactor wraps the last failure once the retries are used up
            Throwable cause = Exceptions.unwrap(e);
            throw new EmbeddingException("embedding request to " + properties.baseUrl()
                    + " failed for a batch of " + batch.size() + " texts", cause);
        }

        if (response == null || response.data() == null) {
            throw new EmbeddingException("embedding backend returned an empty response for a batch of "
                    + batch.size() + " texts");
        }
        return order(response.data(), batch.size());
    }

    /**
     * Places every vector at the position of its input.
     * <p>
     * The protocol carries an explicit {@code index} per item and does not promise array order.
     * Trusting the order would be the worst kind of bug available here: nothing fails, every chunk
     * simply gets a neighbour's vector, and the index answers plausibly and wrongly forever.
     */
    private List<float[]> order(List<EmbeddingItem> items, int expected) {
        if (items.size() != expected) {
            throw new EmbeddingException("embedding backend returned " + items.size()
                    + " vectors for " + expected + " texts");
        }
        float[][] ordered = new float[expected][];
        for (EmbeddingItem item : items) {
            int index = item.index();
            if (index < 0 || index >= expected) {
                throw new EmbeddingException("embedding backend returned index " + index
                        + " for a batch of " + expected + " texts");
            }
            if (ordered[index] != null) {
                throw new EmbeddingException("embedding backend returned index " + index + " twice");
            }
            ordered[index] = normalize(check(item.embedding(), index));
        }
        return List.of(ordered);
    }

    /**
     * Rejects a vector the profile's index could not hold.
     * <p>
     * Without this the mismatch surfaces much later as a rejected bulk item, at a point where
     * nothing points back at the misconfigured model.
     */
    private float[] check(float[] embedding, int index) {
        if (embedding == null) {
            throw new EmbeddingException("embedding backend returned no vector at index " + index);
        }
        if (embedding.length != properties.dimensions()) {
            throw new EmbeddingException("model " + properties.model() + " returned "
                    + embedding.length + " dimensions but the profile expects "
                    + properties.dimensions());
        }
        return embedding;
    }

    /**
     * Scales to unit length.
     * <p>
     * For {@code cosine} this changes nothing - Elasticsearch normalises internally - but
     * {@code dot_product} requires unit vectors and silently ranks nonsense without them. Models
     * that already normalise are unaffected, so this costs a pass over the array and removes a
     * footgun from the similarity setting.
     */
    private static float[] normalize(float[] vector) {
        double sum = 0d;
        for (float value : vector) {
            sum += (double) value * value;
        }
        double length = Math.sqrt(sum);
        if (length == 0d || Double.isNaN(length)) {
            // an all-zero vector cannot be scaled; leave it and let it rank last
            return vector;
        }
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / length);
        }
        return normalized;
    }

    private void authorize(HttpHeaders headers) {
        if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
            headers.setBearerAuth(properties.apiKey());
        }
    }

    /**
     * Retry rate limits, server errors and transport failures. A 400 or a 401 is a configuration
     * problem and repeating it only delays the error.
     */
    private static boolean isTransient(Throwable throwable) {
        if (throwable instanceof WebClientResponseException response) {
            HttpStatusCode status = response.getStatusCode();
            return status.value() == 429 || status.is5xxServerError();
        }
        return throwable instanceof WebClientRequestException;
    }

    static WebClient defaultWebClient(EmbeddingProperties properties) {
        HttpClient httpClient = HttpClient.create()
                // Same reason as in EduSharingConfig: Reactor Netty's native resolver bypasses
                // /etc/hosts and search domains and fails in some K8s/CoreDNS setups.
                .resolver(DefaultAddressResolverGroup.INSTANCE)
                .responseTimeout(properties.timeout());

        return WebClient.builder()
                .baseUrl(properties.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                .build();
    }

    record EmbeddingRequest(String model, List<String> input) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<EmbeddingItem> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingItem(int index, float[] embedding) {
    }
}
