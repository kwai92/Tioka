FROM eclipse-temurin:17-jre
WORKDIR /app
COPY tioka-server/target/tioka-server-*.jar /app/app.jar
ENV JAVA_TOOL_OPTIONS=-Duser.timezone=UTC
ENTRYPOINT ["java","-jar","/app/app.jar"]
