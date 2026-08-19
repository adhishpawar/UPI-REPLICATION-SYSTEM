package com.upi.payment.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * The single outbound HTTP client. Previously injected but never defined,
 * which is one of several reasons this service could not start.
 *
 * <p>Every timeout here is deliberate. A call with no timeout is not
 * "patient", it is a thread held hostage by someone else's outage; enough of
 * them and the caller dies of a dependency's illness. Timeouts are how a
 * service keeps its failure domain to itself.
 *
 * <p>Note what a timeout does <em>not</em> tell you: whether the other side
 * did the work. For a read such as VPA resolution that distinction is
 * irrelevant. For a write that moves money it is everything, which is why a
 * timed-out funds movement goes to UNCERTAIN instead of being retried.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient webClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
                .responseTimeout(Duration.ofSeconds(5))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
