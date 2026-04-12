# Multi-stage build for minimal image size
FROM amazoncorretto:25-alpine-jdk AS build
WORKDIR /app

# Copy Gradle wrapper and config first for better caching
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradlew ./

# Copy source code and build the application
COPY src ./src
RUN ./gradlew build --no-daemon

# Runtime image
FROM amazoncorretto:25-alpine-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# Run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup -h /home/appuser
USER appuser

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
