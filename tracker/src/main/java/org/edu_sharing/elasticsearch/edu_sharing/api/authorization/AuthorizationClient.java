package org.edu_sharing.elasticsearch.edu_sharing.api.authorization;

import org.edu_sharing.generated.repository.backend.services.rest.client.api.AuthenticationV1Api;
import org.edu_sharing.generated.repository.backend.services.rest.client.handler.ApiClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.HttpCookie;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public class AuthorizationClient {

    private record TokenCache(String token, Long time) {
    }

    private final String user;
    private final AuthenticationV1Api authenticationV1Api;
    private CompletableFuture<TokenCache> tokenCacheFuture = CompletableFuture.completedFuture(new TokenCache(null, Instant.now().getEpochSecond()));

    public AuthorizationClient(String uri, String username, String password) {
        ApiClient apiClient = new ApiClient(WebClient.builder().build());
        apiClient.setBasePath(uri);
        apiClient.setUsername(username);
        apiClient.setPassword(password);
        this.user = username;
        this.authenticationV1Api = new AuthenticationV1Api(apiClient);
    }

    public Mono<String> getToken() {
        return Mono.defer(() -> {
            this.invalidate();
            return Mono.fromFuture(this.tokenCacheFuture)
                    .mapNotNull(x -> x.token);
        });
    }

    private synchronized void invalidate() {
        this.tokenCacheFuture = Mono.fromFuture(this.tokenCacheFuture)
                .flatMap(tokenCache -> {
                    if (Instant.now().getEpochSecond() - tokenCache.time < 1 && tokenCache.token != null) {
                        return Mono.just(tokenCache);
                    }

                    return authenticationV1Api.loginWithHttpInfo()
                            .mapNotNull(HttpEntity::getHeaders)
                            .mapNotNull(it -> it.get(HttpHeaders.SET_COOKIE))
                            .mapNotNull(it->it.stream().flatMap(x->HttpCookie.parse(x).stream()).filter(x->x.getName().equals("JSESSIONID")).findFirst().orElse(null))
                            .map(x -> new TokenCache(x.getValue(), Instant.now().getEpochSecond()));
                }).toFuture();
    }


}
