FROM eclipse-temurin:17-jre
WORKDIR /app

# GOV-08：OpenTelemetry Java Agent 1.33.x（自动埋点 Spring MVC / JDBC / Lettuce / HttpClient / RocketMQ）
ADD https://ghproxy.net/https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v1.33.5/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

COPY tioka-server/target/tioka-server-*.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar -Duser.timezone=UTC"
ENV OTEL_SERVICE_NAME=tioka
ENV OTEL_TRACES_EXPORTER=otlp
ENV OTEL_METRICS_EXPORTER=none
ENV OTEL_LOGS_EXPORTER=none

ENTRYPOINT ["java","-jar","/app/app.jar"]
