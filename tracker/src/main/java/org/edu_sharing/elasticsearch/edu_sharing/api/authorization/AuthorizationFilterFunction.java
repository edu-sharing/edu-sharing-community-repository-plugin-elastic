package org.edu_sharing.elasticsearch.edu_sharing.api.authorization;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.util.Strings;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class AuthorizationFilterFunction implements ExchangeFilterFunction {
    // set by the backend on every /rest/* response - "false" when the request was only answered as guest,
    // even with a 200 status (see DESP-377)
    private static final String HEADER_AUTHENTICATED = "X-Edu-Authenticated";

    private final AuthorizationClient authorizationClient;
    private String token;


    @NotNull
    @Override
    public Mono<ClientResponse> filter(@NotNull ClientRequest request, @NotNull ExchangeFunction next) {
        if (Strings.isEmpty(token)) {
            return this.throughGetToken(request, next);
        }
        return withAuthorizationHeader(request, next);
    }

    private Mono<ClientResponse> throughGetToken(ClientRequest request, ExchangeFunction next) {
        return this.authorizationClient.getToken()
                .flatMap(it -> {
                    this.token = it;
                    return withAuthorizationHeader(request, next);
                });
    }

    private Mono<ClientResponse> withAuthorizationHeader(ClientRequest request, ExchangeFunction next) {
        ClientRequest withAuthorizationHeaderRequest = ClientRequest.from(request)
                .cookie("JSESSIONID", this.token)
                .build();

        return next.exchange(withAuthorizationHeaderRequest)
                .flatMap(it -> {
                    if (it.statusCode() == HttpStatus.UNAUTHORIZED) {
                        return it.releaseBody().then(throughGetToken(request, next)) ;
                    }
                    // guest mode may still answer with 200 (or 403, if guest lacks permission on the node) instead
                    // of 401 -> re-login if our ticket was not actually recognized as authenticated
                    if ("false".equalsIgnoreCase(it.headers().asHttpHeaders().getFirst(HEADER_AUTHENTICATED))) {
                        return it.releaseBody().then(throughGetToken(request, next));
                    }

                    return Mono.just(it);
                });
    }
}
