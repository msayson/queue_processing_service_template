---
inclusion: fileMatch
fileMatchPattern: service/**
---

# Service Implementation

Kotlin service that polls SQS and processes messages, deployed as an ECS Fargate container.

## Configuration (environment variables)

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `QUEUE_URL` | yes | — | SQS queue URL to poll |
| `AWS_REGION` | yes | — | AWS region |
| `MAX_MESSAGES` | no | 10 | Batch size for SQS receive |
| `WAIT_TIME_SECONDS` | no | 20 | Long-poll wait time |
| `VISIBILITY_TIMEOUT` | no | 300 | Seconds before unprocessed message reappears |

## Component Responsibilities

- **Main.kt** — loads config, initializes AWS clients, starts `QueuePoller`, handles SIGTERM shutdown
- **QueuePoller.kt** — continuous long-poll loop; delegates to `MessageProcessor`; deletes message on success, leaves it on failure
- **MessageProcessor.kt** — interface: `suspend fun process(messageBody: String, messageId: String)`; implementations must be idempotent
- **TemplateMessageProcessor.kt** — logs message and emits metrics; replace with real business logic when extending
- **MetricsPublisher.kt** — publishes CloudWatch custom metrics; failures are logged but never propagate to stop processing
- **Configuration.kt** — reads env vars, fails fast if required vars are missing

## Error Handling Contract

On `MessageProcessor.process()` throwing:
1. Log error with message ID, body, exception
2. Emit `MessagesProcessedFailure` metric
3. Do **not** delete the message — it reappears after `VISIBILITY_TIMEOUT`
4. SQS redelivers up to 5 times; after that, message routes to DLQ automatically

Transient vs. permanent failure distinction is left to extending projects.

## CloudWatch Metrics

Namespace emitted by `MetricsPublisher`. Dimensions: `Service`, `Environment`.

| Metric | Unit |
|---|---|
| `MessagesReceived` | Count |
| `MessagesProcessedSuccess` | Count |
| `MessagesProcessedFailure` | Count |
| `ProcessingDuration` | Milliseconds |
