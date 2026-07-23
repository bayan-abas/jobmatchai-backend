# Prebuilt by the CI workflow (see .github/workflows/deploy.yml), which runs `mvn package` using
# the same Maven cache the test step already warms - this image only ever copies a jar and runs
# it, never compiles from scratch. Elastic Beanstalk's Docker platform builds this image ON the
# EB instance at deploy time - a full Maven build there (cold dependency download + compile, no
# cache) was regularly exceeding EB's default 600s deployment command timeout, leaving the
# environment stuck on "No Data"/504 with no application ever actually starting.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY app.jar app.jar
# Matches server.port's default (application.properties: server.port=${PORT:8080}) - if you
# override PORT in the Elastic Beanstalk environment, update the EB container port config to match.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
