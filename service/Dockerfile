# syntax=docker/dockerfile:1
# Multi-stage build for minimal image size
# Build stage runs on the builder's native platform: the JAR is platform-independent,
# and running Gradle under QEMU emulation (e.g. arm64 on an x86 host) is ~9x slower.
FROM --platform=$BUILDPLATFORM amazoncorretto:25-alpine-jdk AS build
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
# Mockito, JUnit), which the image build never needs.
# This layer is only invalidated when build.gradle.kts or settings.gradle.kts change,
# not on every source code change. Cache mounts persist the Gradle distribution
# (~150MB) and downloaded dependencies across builds, so a build file change does
# not re-download everything.
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew --no-daemon dependencies --configuration runtimeClasspath

# Copy source code and build the fat JAR only; tests run in CI, not the image build
COPY src ./src
RUN --mount=type=cache,target=/root/.gradle/caches \
    --mount=type=cache,target=/root/.gradle/wrapper \
    ./gradlew --no-daemon shadowJar

# Runtime image
FROM amazoncorretto:25-alpine-jdk
WORKDIR /app
COPY --from=build /app/build/libs/queue-processing-service-all.jar app.jar

# Run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup -h /home/appuser
USER appuser

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
