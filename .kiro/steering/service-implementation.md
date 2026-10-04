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
- **TemplateMessageProcessor.kt** — placeholder processor that logs message details; replace with real business logic when extending
- **MetricsPublisher.kt** — buffers per-call CloudWatch failure and latency observations and drains them in batches
- **Configuration.kt** — reads env vars, fails fast if required vars are missing

## Error Handling Contract

On `MessageProcessor.process()` throwing:
1. Log error with message ID and exception
2. Emit `Failure=1` unless the exception is a bad-input HTTP 4xx (e.g. `ValidationException`; throttling and access denied still count); cancellation emits nothing
3. Do **not** delete the message — it reappears after `VISIBILITY_TIMEOUT`
4. SQS redelivers up to 5 times; after that, message routes to DLQ automatically

Transient vs. permanent failure distinction is left to extending projects.

## CloudWatch Metrics

Namespace emitted by `MetricsPublisher`. Each observation is a separate datum so CloudWatch percentile statistics are preserved.

| Metric | Unit |
|---|---|
| `Failure` | Count (0 for success or cancellation; 1 for other failures) |
| `Latency` | Milliseconds (successful calls only) |

Bad-input HTTP 4xx errors and cancellations emit neither metric. Observations are offered to a bounded 50,000-datum buffer; one daemon thread batches up to 1,000 datums per CloudWatch request every 15 seconds. Full-buffer observations are dropped and counted. Failed batches are logged and discarded. Shutdown drains the remaining buffer for at most 20 s (`FLUSH_TIMEOUT_SECONDS`), then gives up and logs what was left so log flushing still runs before the ECS task timeout.
