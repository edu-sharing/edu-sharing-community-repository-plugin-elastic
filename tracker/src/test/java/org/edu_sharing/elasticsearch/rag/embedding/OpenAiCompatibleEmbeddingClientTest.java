package org.edu_sharing.elasticsearch.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Driven against a real loopback HTTP server rather than a mocked exchange function, because most of
 * what this client does only exists at the protocol level: status codes decide what is retried, the
 * response body decides what is rejected, and neither is observable through a stubbed
 * {@code WebClient}.
 * <p>
 * The reordering test is the important one. A backend that answers out of order breaks nothing
 * visibly - every chunk simply receives a neighbour's vector, and the index goes on answering
 * plausibly and wrongly.
 */
class OpenAiCompatibleEmbeddingClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final List<String> requestBodies = new ArrayList<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Registers the endpoint; the handler maps the parsed request body to a raw response body. */
    private void respondWith(int status, Function<JsonNode, String> body) {
        server.createContext("/v1/embeddings", exchange -> {
            requests.incrementAndGet();
            String requestBody = read(exchange);
            requestBodies.add(requestBody);
            byte[] response = body.apply(JSON.readTree(requestBody)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
    }

    private static String read(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private EmbeddingProperties properties(int dimensions, int batchSize, int maxRetries) {
        return new EmbeddingProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "BAAI/bge-m3", dimensions, null, batchSize,
                Duration.ofSeconds(5), maxRetries, Duration.ofMillis(10));
    }

    private OpenAiCompatibleEmbeddingClient client(EmbeddingProperties properties) {
        return new OpenAiCompatibleEmbeddingClient(
                properties, OpenAiCompatibleEmbeddingClient.defaultWebClient(properties));
    }

    /**
     * Width of the fake vectors. Each carries its declared index as a one-hot component, which -
     * unlike a magnitude - survives normalisation, so an ordering assertion cannot pass by accident.
     */
    private static final int DIM = 4;

    /** One item per input, in the order the request listed them. */
    private static String itemsInOrder(JsonNode request) {
        return items(request.get("input").size(), i -> i);
    }

    private static String items(int count, Function<Integer, Integer> indexAt) {
        StringBuilder json = new StringBuilder("{\"object\":\"list\",\"data\":[");
        for (int i = 0; i < count; i++) {
            int index = indexAt.apply(i);
            json.append(i > 0 ? "," : "")
                    .append("{\"object\":\"embedding\",\"index\":").append(index)
                    .append(",\"embedding\":[").append(oneHot(index)).append("]}");
        }
        return json.append("],\"model\":\"BAAI/bge-m3\"}").toString();
    }

    private static String oneHot(int index) {
        StringBuilder vector = new StringBuilder();
        for (int d = 0; d < DIM; d++) {
            vector.append(d > 0 ? "," : "").append(d == Math.floorMod(index, DIM) ? "1.0" : "0.0");
        }
        return vector.toString();
    }

    // ---- the happy path ----------------------------------------------------------------------

    @Test
    void returnsOneVectorPerText() {
        respondWith(200, OpenAiCompatibleEmbeddingClientTest::itemsInOrder);

        List<float[]> vectors = client(properties(DIM, 32, 0)).embed(List.of("a", "b", "c"));

        assertThat(vectors).hasSize(3);
        assertThat(requests).hasValue(1);
    }

    @Test
    void sendsTheConfiguredModelAndTheTextsThemselves() throws IOException {
        respondWith(200, OpenAiCompatibleEmbeddingClientTest::itemsInOrder);

        client(properties(DIM, 32, 0)).embed(List.of("erster Abschnitt", "zweiter Abschnitt"));

        JsonNode request = JSON.readTree(requestBodies.get(0));
        assertThat(request.get("model").asText()).isEqualTo("BAAI/bge-m3");
        assertThat(request.get("input")).hasSize(2);
        assertThat(request.get("input").get(0).asText()).isEqualTo("erster Abschnitt");
    }

    @Test
    void makesNoRequestForAnEmptyInput() {
        respondWith(200, OpenAiCompatibleEmbeddingClientTest::itemsInOrder);

        assertThat(client(properties(DIM, 32, 0)).embed(List.of())).isEmpty();
        assertThat(requests).hasValue(0);
    }

    // ---- ordering ----------------------------------------------------------------------------

    @Test
    void placesEveryVectorAtThePositionOfItsInput() {
        // the protocol carries an explicit index and does not promise array order
        respondWith(200, request -> items(3, i -> 2 - i));

        List<float[]> vectors = client(properties(DIM, 32, 0)).embed(List.of("a", "b", "c"));

        // each vector lights up the component of its declared index, and a one-hot vector survives
        // normalisation - so a swap between any two positions fails here
        for (int position = 0; position < 3; position++) {
            assertThat(vectors.get(position)[position])
                    .as("vector at position %d", position)
                    .isCloseTo(1.0f, within(0.0001f));
        }
    }

    @Test
    void rejectsADuplicateIndex() {
        respondWith(200, request -> items(3, i -> 1));

        assertThatThrownBy(() -> client(properties(DIM, 32, 0)).embed(List.of("a", "b", "c")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("twice");
    }

    @Test
    void rejectsAnIndexOutsideTheBatch() {
        respondWith(200, request -> items(2, i -> i + 5));

        assertThatThrownBy(() -> client(properties(DIM, 32, 0)).embed(List.of("a", "b")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("index 5");
    }

    @Test
    void rejectsAShortResponse() {
        respondWith(200, request -> items(1, i -> i));

        assertThatThrownBy(() -> client(properties(DIM, 32, 0)).embed(List.of("a", "b", "c")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("1 vectors for 3 texts");
    }

    // ---- batching ----------------------------------------------------------------------------

    @Test
    void splitsIntoBatchesAndKeepsTheOverallOrder() {
        respondWith(200, OpenAiCompatibleEmbeddingClientTest::itemsInOrder);

        List<float[]> vectors = client(properties(DIM, 2, 0)).embed(List.of("a", "b", "c", "d", "e"));

        assertThat(vectors).hasSize(5);
        assertThat(requests).hasValue(3);
        assertThat(requestBodies).hasSize(3);
    }

    // ---- failure handling --------------------------------------------------------------------

    @Test
    void retriesARateLimitAndSucceeds() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/v1/embeddings", exchange -> {
            requests.incrementAndGet();
            String body = read(exchange);
            boolean firstAttempt = attempts.getAndIncrement() == 0;
            byte[] response = (firstAttempt ? "{\"error\":\"slow down\"}" : itemsInOrder(JSON.readTree(body)))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(firstAttempt ? 429 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        List<float[]> vectors = client(properties(DIM, 32, 3)).embed(List.of("a"));

        assertThat(vectors).hasSize(1);
        assertThat(requests).hasValue(2);
    }

    @Test
    void givesUpOnARateLimitThatDoesNotClear() {
        respondWith(429, request -> "{\"error\":\"slow down\"}");

        assertThatThrownBy(() -> client(properties(DIM, 32, 2)).embed(List.of("a")))
                .isInstanceOf(EmbeddingException.class);

        assertThat(requests).hasValue(3); // first attempt plus two retries
    }

    @Test
    void doesNotRetryAConfigurationError() {
        // repeating a rejected request only delays a failure that will not fix itself
        respondWith(400, request -> "{\"error\":\"unknown model\"}");

        assertThatThrownBy(() -> client(properties(DIM, 32, 3)).embed(List.of("a")))
                .isInstanceOf(EmbeddingException.class);

        assertThat(requests).hasValue(1);
    }

    @Test
    void rejectsAModelWhoseVectorsTheIndexCouldNotHold() {
        // caught here, this names the model; caught by Elasticsearch it is a rejected bulk item
        respondWith(200, OpenAiCompatibleEmbeddingClientTest::itemsInOrder);

        assertThatThrownBy(() -> client(properties(1024, 32, 0)).embed(List.of("a")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("returned " + DIM + " dimensions")
                .hasMessageContaining("expects 1024");
    }

    // ---- normalisation -----------------------------------------------------------------------

    @Test
    void scalesVectorsToUnitLength() {
        // dot_product ranks nonsense without this, and cosine is unaffected either way
        respondWith(200, request -> "{\"data\":[{\"index\":0,\"embedding\":[3.0,4.0]}]}");

        float[] vector = client(properties(2, 32, 0)).embed(List.of("a")).get(0);

        assertThat(vector[0]).isCloseTo(0.6f, within(0.0001f));
        assertThat(vector[1]).isCloseTo(0.8f, within(0.0001f));
    }

    @Test
    void leavesAnAllZeroVectorAlone() {
        respondWith(200, request -> "{\"data\":[{\"index\":0,\"embedding\":[0.0,0.0]}]}");

        assertThat(client(properties(2, 32, 0)).embed(List.of("a")).get(0))
                .containsExactly(0.0f, 0.0f);
    }

    // ---- configuration -----------------------------------------------------------------------

    @Test
    void rejectsIncompleteSettingsAtConstruction() {
        assertThatThrownBy(() -> new EmbeddingProperties(null, "m", 1, null, 1,
                Duration.ofSeconds(1), 0, Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingProperties("http://x", "m", 0, null, 1,
                Duration.ofSeconds(1), 0, Duration.ofMillis(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toleratesATrailingSlashInTheBaseUrl() {
        assertThat(EmbeddingProperties.localTei("http://host:8080/").baseUrl())
                .isEqualTo("http://host:8080");
    }
}
