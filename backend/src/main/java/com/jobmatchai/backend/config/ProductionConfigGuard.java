package com.jobmatchai.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

// Fails startup outright when app.environment=prod is paired with a config value that would
// silently produce a broken or dangerous production deployment. Both of these were found (via
// this session's own local restart) to have NO signal at all beyond a comment in
// application.properties - JWT_SECRET has its own equivalent guard in JwtService's constructor;
// this class covers the other one found the same way: SPRING_DATASOURCE_URL left unset falls
// back to an in-memory H2 database that is wiped on every restart/redeploy, which is silent data
// loss in a "production" deployment, not a startup failure.
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
