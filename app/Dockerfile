# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew settings.gradle ./
COPY app/build.gradle app/build.gradle
COPY app/src app/src
RUN ./gradlew :app:bootJar --no-daemon

# Extract layers for better caching
FROM eclipse-temurin:21-jre-alpine AS extract
WORKDIR /workspace
COPY --from=build /workspace/app/build/libs/*.jar application.jar
RUN java -Djarmode=layertools -jar application.jar extract

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=extract /workspace/dependencies/ ./
COPY --from=extract /workspace/spring-boot-loader/ ./
COPY --from=extract /workspace/snapshot-dependencies/ ./
COPY --from=extract /workspace/application/ ./

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -q --spider http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
