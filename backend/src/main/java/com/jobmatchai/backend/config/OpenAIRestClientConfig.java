package com.jobmatchai.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

// The RestClient every OpenAI-calling service shares - previously each service built its own
// inline RestClient.builder()...build() with no timeout configured at all, so a hung OpenAI
// request (network stall, provider outage) would block its thread and the semaphore permit it
// holds (see JobMatchService#MAX_CONCURRENT_MATCH_CALLS) indefinitely, with no bound. A bounded
// connect/read timeout is what lets the existing catch-and-retry logic in
// JobMatchService#computeChunkWithRetry (an exception from a stuck call already triggers its one
// retry) actually get a chance to fire, instead of the call just hanging forever.
@Configuration
public class OpenAIRestClientConfig {

    @Value("${openai.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${openai.read-timeout-ms:45000}")
    private int readTimeoutMs;

    @Bean("openAIRestClient")
    public RestClient openAIRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
                .baseUrl("https://api.openai.com")
                .requestFactory(requestFactory)
                .build();
    }
}
