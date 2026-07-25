package com.jobmatchai.backend.service.provider;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

final class ExternalJobRestClients {

    private ExternalJobRestClients() {
    }

    // בונה RestClient עם timeout קבוע לחיבור ולקריאה, כדי שספק חיצוני איטי לא יתקע את הבקשה לנצח
    static RestClient.Builder timeoutBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));

        return RestClient.builder().requestFactory(requestFactory);
    }
}
