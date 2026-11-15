FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/target/os-payment-0.1.0.jar app.jar
# Datadog Java tracer. Loaded only when JAVA_TOOL_OPTIONS points at it, which the
# ECS task definition does when Datadog is enabled; locally it stays inert.
ARG DD_JAVA_AGENT_URL=https://dtdg.co/latest-java-tracer
ADD --chown=app:app ${DD_JAVA_AGENT_URL} /app/dd-java-agent.jar
USER app
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
