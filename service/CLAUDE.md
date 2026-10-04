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

**Message flow:** SQS → `WorkerManager` runs `NUM_WORKER_THREADS` parallel `QueuePoller` coroutines (each long-polls in batches of up to 10) → `MessageProcessor` (interface) → on success: delete from queue; on failure: retain for retry via visibility timeout (5 min, up to 5 attempts) → Dead Letter Queue after exhaustion.

**Components** (`src/main/kotlin/com/template/queue/`):
- `Main.kt` — entry point; initializes AWS SQS and CloudWatch clients, wires dependencies, registers shutdown hook (stops workers within grace period, then flushes metrics)
- `WorkerManager.kt` — launches/stops the worker coroutines on `Dispatchers.IO`; waits up to `WORKER_SHUTDOWN_GRACE_PERIOD_SECONDS` for in-flight work on shutdown
- `QueuePoller.kt` — long-polling loop (20s wait time), batch receive, delegates to `MessageProcessor`, handles deletion
- `MessageProcessor.kt` — interface with a single `suspend fun process(messageBody: String, messageId: String)` method; implement this to add business logic
- `TemplateMessageProcessor.kt` — placeholder implementation; **replace with actual processing logic**
- `MetricsPublisher.kt` — buffers and batches per-call `Failure` / `Latency` CloudWatch observations
- `config/Configuration.kt` — reads all config from environment variables

**Environment variables:**
| Variable | Required | Default | Description |
|---|---|---|---|
| `QUEUE_URL` | Yes | — | SQS queue URL |
| `AWS_REGION` | Yes | — | AWS region |
| `MAX_MESSAGES` | No | 10 | Batch size (1–10) |
| `WAIT_TIME_SECONDS` | No | 20 | Long polling timeout |
| `VISIBILITY_TIMEOUT` | No | 300 | Message lock duration (seconds) |
| `NUM_WORKER_THREADS` | No | 1 | Parallel polling workers per process |
| `WORKER_SHUTDOWN_GRACE_PERIOD_SECONDS` | No | 30 | Wait for in-flight workers on shutdown (seconds) |

## Testing

Unit tests use JUnit 5 + Mockito (`mockito-kotlin`). Coroutine tests use `runTest` from `kotlinx.coroutines.test`. All AWS SDK clients are mocked at the test boundary.

Local integration tests live under `src/test/kotlin/com/template/queue/localinteg/` and are tagged `@Tag("localIntegTest")`. See `service/README.md` for how to run them.
