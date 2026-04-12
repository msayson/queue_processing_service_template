# Multi-stage build for minimal image size
FROM amazoncorretto:25-alpine-jdk AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradlew ./
COPY gradle ./gradle
COPY src ./src
RUN ./gradlew build --no-daemon

# Runtime image
FROM amazoncorretto:25-alpine-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# Run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

ENTRYPOINT ["java", "-jar", "app.jar"]
