# Multi-stage build for minimal image size
FROM amazoncorretto:25-alpine-jdk AS build
WORKDIR /app

# Copy Gradle wrapper and config first for better caching
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradlew ./

# Copy source code and build the application
COPY src ./src
# Disable Foojay toolchain auto-download and point Gradle at the JDK already
# present in this image. Without this, Gradle queries the Foojay API to
# provision a JDK, which fails (or times out) in the Alpine/musl environment.
# Properties must be written to gradle.properties — -D flags on the client JVM
# do not propagate to the forked daemon process.
RUN mkdir -p /root/.gradle && \
    printf 'org.gradle.java.home=%s\norg.gradle.java.installations.auto-download=false\norg.gradle.java.installations.paths=%s\n' \
    "$JAVA_HOME" "$JAVA_HOME" > /root/.gradle/gradle.properties
RUN ./gradlew build --no-daemon

# Runtime image
FROM amazoncorretto:25-alpine-jdk
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# Run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup -h /home/appuser
USER appuser

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
