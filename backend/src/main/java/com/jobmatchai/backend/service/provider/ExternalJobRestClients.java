package com.jobmatchai.backend.service.provider;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

// Shared bounded-timeout RestClient builder for the external job-board providers (JSearch,
// Jooble, Jobicy). Previously each provider built its own bare RestClient.builder()...build()
// with no timeout configured at all, so a stalled response from any one of these third-party APIs
// could block indefinitely - and since these providers are called sequentially from
// ExternalJobService's @Scheduled import cron, a single hung call didn't just delay that job, it
// occupied the app's shared scheduler thread (see spring.task.scheduling.pool.size in
// application.properties) for as long as the stall lasted, starving every other scheduled task
// (including the match-score queue poller) in the meantime.
final class ExternalJobRestClients {

    private ExternalJobRestClients() {
    }

    static RestClient.Builder timeoutBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));

        return RestClient.builder().requestFactory(requestFactory);
    }
}
