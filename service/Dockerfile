# syntax=docker/dockerfile:1
# Multi-stage build for minimal image size
FROM amazoncorretto:25-alpine-jdk AS build
WORKDIR /app

# Copy Gradle wrapper and config first for better caching
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradlew ./

# Disable Foojay toolchain auto-download and point Gradle at the JDK already
# present in this image.
RUN mkdir -p /root/.gradle && \
    printf 'org.gradle.java.home=%s\norg.gradle.java.installations.auto-download=false\norg.gradle.java.installations.paths=%s\n' \
    "$JAVA_HOME" "$JAVA_HOME" > /root/.gradle/gradle.properties

# Resolve and download runtime dependencies before copying source.
# Scoped to runtimeClasspath to avoid downloading test-only dependencies (Testcontainers,
# Mockito, JUnit) here — those are fetched during the build step instead.
# This layer is only invalidated when build.gradle.kts or settings.gradle.kts change,
# not on every source code change. The wrapper cache mount avoids re-downloading
# the Gradle distribution (~150MB) on every build.
RUN --mount=type=cache,target=/root/.gradle/wrapper/dists \
    ./gradlew --no-daemon dependencies --configuration runtimeClasspath

# Copy source code and build
COPY src ./src
RUN ./gradlew build --no-daemon

# Runtime image
FROM amazoncorretto:25-alpine-jdk
WORKDIR /app
COPY --from=build /app/build/libs/queue-processing-service-all.jar app.jar

# Run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup -h /home/appuser
USER appuser

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
