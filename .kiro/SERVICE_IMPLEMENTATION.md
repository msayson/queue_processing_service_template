# Service Implementation Guide

## Overview
This document guides the implementation of the Kotlin service that polls SQS and processes messages. The service should be minimal, focused, and serve as a template for extension.

## Kotlin Project Setup

> All versions are defined in `VERSIONS.md`. Use those versions when generating code.

### Build Configuration (Gradle)

**build.gradle.kts** (Gradle Kotlin DSL):
```kotlin
plugins {
    kotlin("jvm") version "<kotlin.version>"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // AWS SDK for Kotlin
    implementation(platform("aws.sdk.kotlin:bom:<aws-sdk-kotlin-bom.version>"))
    implementation("aws.sdk.kotlin:sqs")
    implementation("aws.sdk.kotlin:cloudwatch")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:<kotlinx-coroutines.version>")

    // Logging
    implementation("io.github.oshai:kotlin-logging-jvm:<kotlin-logging-jvm.version>")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:<log4j.version>")

    // JSON parsing (for message bodies)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:<kotlinx-serialization.version>")
}

application {
    mainClass.set("com.template.queue.MainKt")
}
```

**settings.gradle.kts**:
```kotlin
rootProject.name = "queue-processing-service"
```

### Project Structure
```
service/
├── src/
│   ├── main/
│   │   ├── kotlin/com/template/queue/
│   │   │   ├── Main.kt                    # Application entry point
│   │   │   ├── QueuePoller.kt             # SQS polling logic
│   │   │   ├── MessageProcessor.kt        # Message processing interface
│   │   │   ├── TemplateMessageProcessor.kt # Template implementation
│   │   │   ├── MetricsPublisher.kt        # CloudWatch metrics
│   │   │   └── config/
│   │   │       └── Configuration.kt       # Environment config
│   │   └── resources/
│   │       └── log4j2.xml                 # Logging configuration
│   └── test/
│       └── kotlin/com/template/queue/
│           └── MessageProcessorTest.kt
├── build.gradle.kts
└── Dockerfile
```

## Core Components

### 1. Main Application (Main.kt)
**Responsibilities**:
- Load configuration from environment variables
- Initialize AWS SDK clients (SQS, CloudWatch)
- Create and start QueuePoller
- Handle graceful shutdown on SIGTERM

**Key behaviors**:
- Run continuously until interrupted
- Catch and log any unhandled exceptions
- Ensure proper resource cleanup on shutdown

### 2. Configuration (Configuration.kt)
**Environment Variables**:
- `QUEUE_URL` (required): SQS queue URL
- `AWS_REGION` (required): AWS region
- `MAX_MESSAGES` (default: 10): Batch size for SQS receive
- `WAIT_TIME_SECONDS` (default: 20): Long polling wait time
- `VISIBILITY_TIMEOUT` (default: 300): Message visibility timeout in seconds

**Validation**:
- Fail fast if required variables are missing
- Log configuration on startup (excluding sensitive data)

### 3. Queue Poller (QueuePoller.kt)
**Responsibilities**:
- Poll SQS queue continuously using long polling
- Receive messages in batches (up to MAX_MESSAGES)
- Delegate processing to MessageProcessor
- Delete successfully processed messages
- Handle processing failures (log, emit metrics, don't delete)

**Implementation details**:
- Use `receiveMessage` with `WaitTimeSeconds` for long polling
- Process messages concurrently using Kotlin coroutines
- Catch exceptions per message (don't let one failure stop batch)
- Use structured logging with message ID context

**Polling loop**:
```
while (running) {
    messages = receiveMessages()
    for (message in messages) {
        try {
            process(message)
            deleteMessage(message)
            emitSuccessMetric()
        } catch (e: Exception) {
            logError(message, e)
            emitFailureMetric()
            // Message remains in queue, will be retried
        }
    }
}
```

### 4. Message Processor Interface (MessageProcessor.kt)
**Interface definition**:
```kotlin
interface MessageProcessor {
    suspend fun process(messageBody: String, messageId: String)
}
```

**Contract**:
- Implementations must be idempotent
- Throw exceptions on processing failures
- Should not delete messages (handled by poller)

### 5. Template Implementation (TemplateMessageProcessor.kt)
**Template behavior** (to be replaced by extending projects):
1. Parse message body (assume JSON format)
2. Log the received message with structured fields:
   - Message ID
   - Timestamp
   - Message body (truncated if large)
3. Emit CloudWatch metric: `MessageReceived`
4. Simulate processing (can add small delay if desired)
5. Log successful processing

**Example log output**:
```
INFO  [MessageProcessor] Processing message
  messageId: abc-123-def
  timestamp: 2026-04-11T14:30:00Z
  body: {"eventType":"DELETE_DATA","resourceId":"res-456"}

INFO  [MessageProcessor] Message processed successfully
  messageId: abc-123-def
  processingTimeMs: 45
```

### 6. Metrics Publisher (MetricsPublisher.kt)
**Responsibilities**:
- Publish custom metrics to CloudWatch
- Batch metrics when possible for efficiency
- Handle CloudWatch API failures gracefully

**Metrics to emit**:
1. `MessagesReceived` (Count): Number of messages received
2. `MessagesProcessedSuccess` (Count): Successfully processed
3. `MessagesProcessedFailure` (Count): Processing failures
4. `ProcessingDuration` (Milliseconds): Time to process message

**Metric dimensions**:
- `Service`: Service name
- `Environment`: dev/staging/prod (if applicable)

**Error handling**:
- Log metric publishing failures but don't fail processing
- Consider buffering metrics and retrying

## Error Handling Strategy

### Processing Failures
When `MessageProcessor.process()` throws an exception:
1. Log error with full context:
   - Message ID
   - Message body
   - Exception type and message
   - Stack trace
2. Emit `MessagesProcessedFailure` metric
3. Do NOT delete message from queue
4. Message becomes visible again after visibility timeout (5 minutes)
5. SQS will redeliver up to 5 times (configured in queue)
6. After 5 failures, message moves to DLQ automatically

### Transient vs. Permanent Failures
Template doesn't distinguish (extending projects should):
- **Transient**: Network issues, rate limits → Retry helpful
- **Permanent**: Invalid message format, business rule violation → Retry won't help

Extending projects should:
- Detect permanent failures
- Log and emit specific metrics
- Optionally delete message immediately (send to DLQ manually)

### Unhandled Exceptions
If the main polling loop crashes:
1. Log the exception
2. Attempt graceful shutdown
3. Exit with non-zero code
4. ECS will restart the task automatically

## Logging Standards

### Log Levels
- **ERROR**: Processing failures, unhandled exceptions, critical issues
- **WARN**: Retryable errors, degraded performance
- **INFO**: Message processing lifecycle, startup/shutdown
- **DEBUG**: Detailed message content, AWS API calls

### Structured Logging
Use structured fields for machine parsing:
```
{
  "timestamp": "2026-04-11T14:30:00Z",
  "level": "INFO",
  "logger": "MessageProcessor",
  "message": "Processing message",
  "messageId": "abc-123",
  "eventType": "DELETE_DATA"
}
```

### Log Retention
Configured in CDK (default 7 days for template).

## Idempotency

### Requirements
Processing the same message multiple times must produce the same result without side effects.

### Template Implementation
The template simply logs and emits metrics, which is naturally idempotent.

### Guidance for Extending Projects
Implement idempotency using:
1. **Idempotency keys**: Store message ID in database, check before processing
2. **Conditional operations**: Use database constraints or conditional writes
3. **Immutable operations**: Design operations that are safe to repeat

### Performance Considerations

### Throughput
- Target: 10,000 messages/day = ~7 messages/minute average
- Single Fargate task (0.5 vCPU, 2 GB) with batch size 10 is sufficient
- Each poll can retrieve up to 10 messages
- With 20-second long polling, can process 30 messages/minute (4x headroom)

### Scaling
For higher throughput, extending projects can:
1. Increase ECS service max capacity (horizontal scaling)
2. Increase Fargate task CPU/memory (vertical scaling)
3. Auto-scaling based on CPU (55-85% target) and queue depth
4. Scale to 0 tasks when queue is empty for 15 minutes (all environments)
5. CPU-based scaling never scales to 0 (only queue-based scaling does)

### Cold Start Considerations
- Scale-to-zero means first message may experience 2-3 minute delay
- ECS must start task, pull image, and begin processing
- Acceptable for 99% within 15 minutes SLA
- For lower latency requirements, set min capacity to 1
- Once tasks are running, CPU-based scaling maintains capacity while messages exist

### Latency
- 99% within 15 minutes: Achieved with continuous polling
- Long polling (20s) minimizes empty receives
- Visibility timeout (5 min) allows retries without excessive delay

## Docker Configuration

### Dockerfile

> All base image versions are defined in `VERSIONS.md`. Use those versions when generating code.

```dockerfile
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
```

**Key points**:
- Multi-stage build (smaller final image)
- Amazon Corretto 25 on Alpine Linux
- ARM64 compatible (builds for linux/arm64 platform)
- JRE for runtime (not full JDK)
- Non-root user for security
- No hardcoded credentials
- Includes Amazon Corretto Crypto Provider (ACCP) for faster TLS/encryption

**Building for ARM64**:
```bash
# Build for ARM64 (Graviton)
docker buildx build --platform linux/arm64 -t queue-processing-service:latest .

# Or build multi-platform (ARM64 + x86_64)
docker buildx build --platform linux/arm64,linux/amd64 -t queue-processing-service:latest .
```

**Image size**: ~180-220 MB (Alpine JRE)

**Performance benefits**:
- ACCP provides 28x faster AES-GCM encryption
- 13x faster elliptic curve operations
- Optimized for AWS infrastructure (Graviton, Nitro)

### Health Checks
ECS can monitor task health via:
- Container exit code (non-zero = unhealthy)
- CloudWatch metrics (no messages processed = potential issue)

Extending projects can add HTTP health endpoint if needed.

## Testing

### Unit Tests
Test `MessageProcessor` implementations:
- Valid message processing
- Invalid message handling
- Exception scenarios
- Idempotency verification

### Integration Tests
Test with LocalStack or actual AWS resources:
- End-to-end message flow
- Retry behavior
- DLQ routing
- Metrics emission

### Local Development
Run locally with:
1. LocalStack for SQS and CloudWatch
2. Environment variables pointing to local endpoints
3. Docker Compose for orchestration

## Extension Points

Projects extending this template should:

1. **Replace TemplateMessageProcessor**:
   - Implement actual business logic
   - Add domain-specific validation
   - Integrate with databases, APIs, etc.

2. **Add dependencies**:
   - Database drivers (PostgreSQL, DynamoDB, etc.)
   - HTTP clients for external APIs
   - Domain-specific libraries

3. **Enhance error handling**:
   - Distinguish transient vs. permanent failures
   - Add custom retry logic
   - Implement circuit breakers for external dependencies

4. **Add observability**:
   - Custom metrics for business KPIs
   - Distributed tracing (X-Ray)
   - Structured logging with correlation IDs

5. **Update IAM permissions**:
   - Add permissions for additional AWS services
   - Use least-privilege principle

6. **Implement health checks**:
   - HTTP endpoint for ECS health checks
   - Liveness and readiness probes
