FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 appuser
COPY --from=build /workspace/target/qc-meta-service.jar /app/app.jar
USER appuser
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
