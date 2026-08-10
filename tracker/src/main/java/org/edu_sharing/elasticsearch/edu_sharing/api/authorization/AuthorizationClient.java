package org.edu_sharing.elasticsearch.edu_sharing.api.authorization;

import io.netty.resolver.DefaultAddressResolverGroup;
import lombok.extern.slf4j.Slf4j;
import org.edu_sharing.generated.repository.backend.services.rest.client.api.AuthenticationV1Api;
import org.edu_sharing.generated.repository.backend.services.rest.client.handler.ApiClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.HttpCookie;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class AuthorizationClient {

    private record TokenCache(String token, Long time) {
    }

    private final String user;
    private final AuthenticationV1Api authenticationV1Api;
    private CompletableFuture<TokenCache> tokenCacheFuture = CompletableFuture.completedFuture(new TokenCache(null, Instant.now().getEpochSecond()));

    public AuthorizationClient(String uri, String username, String password) {
        // Same JDK resolver (getaddrinfo) as the main WebClient (EduSharingConfig#webClient):
        // Reactor Netty's native resolver bypasses /etc/hosts/nsswitch and fails with SERVFAIL
        // in some K8s/CoreDNS setups where curl resolves fine, leaving the login (and therefore
        // the readiness probe) stuck even though the repository is reachable.
        HttpClient httpClient = HttpClient.create().resolver(DefaultAddressResolverGroup.INSTANCE);
        ApiClient apiClient = new ApiClient(WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient)).build());
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
                            .doOnSubscribe(s -> log.debug("Logging in to repository as '{}' ...", user))
                            .doOnError(e -> log.warn("Login to repository as '{}' failed: {}", user, e.getMessage()))
                            .mapNotNull(HttpEntity::getHeaders)
                            .mapNotNull(it -> it.get(HttpHeaders.SET_COOKIE))
                            .mapNotNull(it->it.stream().flatMap(x->HttpCookie.parse(x).stream()).filter(x->x.getName().equals("JSESSIONID")).findFirst().orElse(null))
                            .map(x -> new TokenCache(x.getValue(), Instant.now().getEpochSecond()))
                            .doOnNext(x -> log.debug("Login to repository as '{}' succeeded", user));
                }).toFuture();
    }


}
