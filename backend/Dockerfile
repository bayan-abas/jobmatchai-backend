# Both stages pinned to 21 to match pom.xml's <java.version>21</java.version>/<release>21> - a
# newer JRE can usually still run older-targeted bytecode, but there's no reason to rely on that
# when the build stage can just compile with the same JDK version the app is actually written for.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Matches server.port's default (application.properties: server.port=${PORT:8080}) - if you
# override PORT in the Elastic Beanstalk environment, update the EB container port config to match.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
