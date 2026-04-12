# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this directory.

## Commands

```bash
# Build
./gradlew build

# Run tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.template.queue.QueuePollerTest"

# Run locally
export QUEUE_URL=https://sqs.us-east-1.amazonaws.com/123456789012/MyQueue
export AWS_REGION=us-east-1
./gradlew run

# Build Docker image
docker build -t queue-processing-service:latest .

# Build for ARM64/Graviton2 (matches production ECS Fargate)
docker buildx build --platform linux/arm64 -t queue-processing-service:latest .
```

## Architecture

**Message flow:** SQS → `QueuePoller` (long-polls in batches of up to 10) → `MessageProcessor` (interface) → on success: delete from queue; on failure: retain for retry via visibility timeout (5 min, up to 5 attempts) → Dead Letter Queue after exhaustion.

**Components** (`src/main/kotlin/com/template/queue/`):
- `Main.kt` — entry point; initializes AWS SQS and CloudWatch clients, wires dependencies, registers graceful shutdown hook
- `QueuePoller.kt` — long-polling loop (20s wait time), batch receive, delegates to `MessageProcessor`, handles deletion
- `MessageProcessor.kt` — interface with a single `suspend fun process(body: String, messageId: String)` method; implement this to add business logic
- `TemplateMessageProcessor.kt` — placeholder implementation; **replace with actual processing logic**
- `MetricsPublisher.kt` — publishes `MessagesProcessedSuccess` / `MessagesProcessedFailure` CloudWatch metrics
- `config/Configuration.kt` — reads all config from environment variables

**Environment variables:**
| Variable | Required | Default | Description |
|---|---|---|---|
| `QUEUE_URL` | Yes | — | SQS queue URL |
| `AWS_REGION` | Yes | — | AWS region |
| `MAX_MESSAGES` | No | 10 | Batch size (1–10) |
| `WAIT_TIME_SECONDS` | No | 20 | Long polling timeout |
| `VISIBILITY_TIMEOUT` | No | 300 | Message lock duration (seconds) |

## Testing

Tests use JUnit 5 + Mockito (`mockito-kotlin`). Coroutine tests use `runTest` from `kotlinx.coroutines.test`. All AWS SDK clients are mocked at the test boundary — no real AWS calls are made in tests.

Test files mirror the main source structure under `src/test/kotlin/com/template/queue/`.
