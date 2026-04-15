# Queue Processing Service

Kotlin-based queue processing service for AWS SQS.

## Build

```bash
./gradlew build
```

## Run Locally

```bash
export QUEUE_URL=https://sqs.us-east-1.amazonaws.com/123456789012/MyQueue
export AWS_REGION=us-east-1
./gradlew run
```

## Docker Build

```bash
docker build -t queue-processing-service:latest .
```

## Local Integration Tests

Requires Docker to be installed and running.

If using Docker Desktop, start the Docker Desktop application before running, otherwise integration tests may fail with a `java.lang.IllegalStateException: Could not find a valid Docker environment. Please see logs and check configuration` error.

```bash
./gradlew localIntegTest -DrunLocalIntegTests=true
```

This uses Testcontainers + LocalStack to spin up a local SQS instance, then runs the actual `QueuePoller` and `TemplateMessageProcessor` against it to verify end-to-end message processing and deletion.

## Structure

- `src/main/kotlin/com/template/queue/` - Application code
  - `Main.kt` - Entry point
  - `QueuePoller.kt` - SQS polling logic
  - `MessageProcessor.kt` - Processing interface
  - `TemplateMessageProcessor.kt` - Template implementation
  - `MetricsPublisher.kt` - CloudWatch metrics
  - `config/Configuration.kt` - Environment configuration
