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

## Structure

- `src/main/kotlin/com/template/queue/` - Application code
  - `Main.kt` - Entry point
  - `QueuePoller.kt` - SQS polling logic
  - `MessageProcessor.kt` - Processing interface
  - `TemplateMessageProcessor.kt` - Template implementation
  - `MetricsPublisher.kt` - CloudWatch metrics
  - `config/Configuration.kt` - Environment configuration
