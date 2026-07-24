# Self-contained multi-stage build - Render builds this image directly from source on every
# deploy (no separate CI packaging step required, unlike the old Elastic Beanstalk setup this
# replaced). The build stage compiles the jar; only the finished jar is copied into the slim
# runtime image below, so the final image never carries the JDK/Maven toolchain or build cache.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Copied separately from src/ so Docker's layer cache can skip re-downloading dependencies on
# every rebuild when only application code changed, not pom.xml.
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline
COPY src src
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
# Matches server.port's default (application.properties: server.port=${PORT:8080}) - Render sets
# PORT automatically and the app already listens on whatever it's given.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
