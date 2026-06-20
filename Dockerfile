FROM gradle:9.5-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon build

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV PCA_PORT=8356
ENV PCA_CONFIG_DIR=/config
COPY --from=build /workspace/build/libs/photo-culling-assistant.jar /app/photo-culling-assistant.jar
EXPOSE 8356
ENTRYPOINT ["java", "-cp", "/app/photo-culling-assistant.jar", "com.photocull.server.ServerApp"]

