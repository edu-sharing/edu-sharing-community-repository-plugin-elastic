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
                    // we need to check both 401 and 403 -> if guest mode is enabled we get 403 instead
                    // TODO we still have a lag of information when the endpoint is accessible by guest but doesn't return all data
                    if (it.statusCode() == HttpStatus.UNAUTHORIZED || it.statusCode() == HttpStatus.FORBIDDEN) {
                        return throughGetToken(request, next);
                    }

                    return Mono.just(it);
                });
    }
}
