package com.jobmatchai.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class OpenAIRestClientConfig {

    @Value("${openai.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${openai.read-timeout-ms:45000}")
    private int readTimeoutMs;

    // RestClient ייעודי ל-OpenAI עם timeout ארוך לקריאה, כי תשובות מודל AI יכולות לקחת הרבה יותר זמן מבקשה רגילה
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
