package com.jobmatchai.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class ProductionConfigGuard {

    private final String environment;
    private final String datasourceUrl;

    public ProductionConfigGuard(
            @Value("${app.environment:dev}") String environment,
            @Value("${spring.datasource.url}") String datasourceUrl
    ) {
        this.environment = environment;
        this.datasourceUrl = datasourceUrl;
    }

    // מונע עליית שרת בסביבת production כשמישהו שכח להגדיר מסד נתונים אמיתי ונשאר על H2 בזיכרון שנמחק בכל ריסטארט
    @PostConstruct
    public void validate() {
        if ("prod".equals(environment) && datasourceUrl.startsWith("jdbc:h2:mem:")) {
            throw new IllegalStateException(
                    "app.environment=prod but SPRING_DATASOURCE_URL was never set - refusing to start "
                            + "against an in-memory H2 database that is wiped on every restart/redeploy. "
                            + "Point SPRING_DATASOURCE_URL at a persistent database (e.g. Postgres) before "
                            + "deploying.");
        }
    }
}
