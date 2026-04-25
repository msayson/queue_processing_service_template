# Queue Processing Service Template

A template for building an AWS ECS Fargate queue processing service in Kotlin. This template provides infrastructure and service code for a service behind a private subset that polls an input SQS queue, auto-scales up/down based on backlog size, processes messages idempotently, handles failures gracefully, and emits observability metrics to CloudWatch.

Hosting ECS compute services in a private subset is a recommended default for ensuring the service is only accessible from allow-listed services, and not from the public internet.  For low-risk personal projects with non-sensitive data, you can lower costs by hosting the service in a public subset and dropping the NAT gateway to save $32/month.

## Features

- **Kotlin Service**: Modern, type-safe JVM application using AWS SDK for Kotlin
- **Infrastructure as Code**: Complete AWS CDK setup in TypeScript
- **AWS ECS Fargate**: Serverless container hosting with auto-scaling capabilities
- **SQS Integration**: Long polling, batch processing, automatic retries, and DLQ
- **Observability**: CloudWatch logs and custom metrics for visibility
- **Template Design**: Minimal implementation for easy extension

## Architecture

```
┌─────────────┐
│   Message   │
│   Producer  │
└──────┬──────┘
       │
       ▼
┌─────────────────────────────────────────────────────┐
│                    SQS Queue                        │
│  • Visibility timeout: 5 minutes                    │
│  • Max receives: 5                                  │
│  • Long polling: 20 seconds                         │
└──────────────┬──────────────────────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────┐
│              ECS Fargate Service                    │
│  ┌───────────────────────────────────────────────┐  │
│  │  Kotlin Application                           │  │
│  │  • Polls SQS continuously                     │  │
│  │  • Processes messages (template: log + emit)  │  │
│  │  • Deletes on success                         │  │
│  │  • Retries on failure (up to 5x)              │  │
│  └───────────────────────────────────────────────┘  │
└──────────────┬──────────────────────────────────────┘
               │
               ├─────────────────┐
               │                 │
               ▼                 ▼
    ┌──────────────────┐  ┌──────────────┐
    │  CloudWatch      │  │  SQS DLQ     │
    │  • Logs          │  │  (after 5    │
    │  • Metrics       │  │   failures)  │
    └──────────────────┘  └──────────────┘
```

## Quick Start

### Prerequisites

- **AWS account** with permissions to create VPC, ECS, SQS, ECR, IAM, and CloudWatch resources
- **AWS CLI v2+** configured with credentials (`aws configure`)
- **Node.js 24+** (for CDK)
- **Docker Desktop** — see platform notes below

#### Docker Desktop setup (Windows)

1. Install [Docker Desktop for Windows](https://docs.docker.com/desktop/setup/install/windows-install/)
2. In Docker Desktop → Settings → Resources → WSL Integration, enable integration for your WSL2 distro
3. Ensure the Linux engine is selected (not Windows containers)
4. Confirm Docker is reachable from your terminal before running the deploy script:
   ```bash
   docker info
   ```

### Deploy

`scripts/deploy.sh` handles the full deployment in the correct order: VPC stack → ECR repository → Docker build and push → service stack → ECS force-deploy.

```bash
./scripts/deploy.sh --awsAccountId 123456789012 --awsRegion us-east-1
```

What the script does:
1. Installs CDK dependencies and bootstraps the CDK environment
2. Deploys `VpcStack` (VPC, NAT gateway)
3. Creates the ECR repository if it doesn't exist
4. Builds the Docker image (`linux/arm64`) and pushes it to ECR
5. Deploys `QueueProcessingServiceStack` (SQS, ECS, IAM, CloudWatch)
6. Forces a new ECS deployment to pull the latest image

### Verify

```bash
# View logs
# Note: when running on Windows, may need to use following instead to avoid the log group name being interpreted as a local filepath: MSYS_NO_PATHCONV=1 aws logs tail "/ecs/queue-processing-service" --follow
aws logs tail /ecs/queue-processing-service --follow

# Send a test message (replace QUEUE_URL with the QueueUrl stack output)
aws sqs send-message \
  --queue-url "https://sqs.${REGION}.amazonaws.com/${ACCOUNT_ID}/QueueProcessingService-InputQueue" \
  --message-body '{"eventType":"TEST","data":"Hello World"}'
```

## Project Structure

```
queue_processing_service_template/
├── .kiro/                          # Kiro steering documents
│   ├── PROJECT_OVERVIEW.md         # Architecture and requirements
│   ├── INFRASTRUCTURE.md           # CDK implementation guide
│   ├── DEPLOYMENT.md               # Build and deployment guide
│   └── steering/
│       └── service-implementation.md  # Kotlin service guide (service/** only)
├── docs/                           # Design documents and diagrams
├── infrastructure/                 # AWS CDK code (TypeScript)
│   ├── bin/                        # CDK app entry point
│   ├── lib/                        # Stack definitions
│   ├── cdk.json
│   ├── package.json
│   └── tsconfig.json
├── service/                        # Kotlin service code
│   ├── src/
│   │   ├── main/kotlin/            # Application code
│   │   └── test/kotlin/            # Tests
│   ├── build.gradle.kts
│   └── Dockerfile
├── .gitignore
├── LICENSE
└── README.md
```

## Template Behavior

The template implementation provides a minimal, working example:

1. **Polls SQS queue** continuously using long polling
2. **Receives messages** in batches (up to 10)
3. **Processes each message**:
   - Logs message ID and body
   - Emits CloudWatch metric `MessageReceived`
   - Simulates processing (placeholder for business logic)
4. **On success**: Deletes message from queue
5. **On failure**:
   - Logs error with full context
   - Emits CloudWatch metric `MessagesProcessedFailure`
   - Message remains in queue and retries after 5 minutes
   - After 5 failures, message moves to DLQ

## Extending This Template

To build your own queue processing service:

1. **Clone this repository**
2. **Implement business logic** in `service/src/main/kotlin/`:
   - Replace `TemplateMessageProcessor` with your implementation
   - Add domain-specific validation and error handling
   - Integrate with databases, APIs, or other AWS services
3. **Update infrastructure** in `infrastructure/lib/`:
   - Add required AWS resources (RDS, DynamoDB, S3, etc.)
   - Update IAM permissions for additional services
   - Add custom CloudWatch alarms and dashboards
4. **Customize configuration**:
   - Adjust Fargate task size (CPU, memory)
   - Tune SQS settings (batch size, visibility timeout)
   - Configure auto-scaling policies
5. **Deploy** using the same process

## Key Features

### Idempotency
The template is designed for idempotent processing. Extending projects should implement idempotency using:
- Idempotency keys (store message ID before processing)
- Conditional database operations
- Immutable operations

### Error Handling
- **Transient failures**: Automatic retry with exponential backoff (via SQS visibility timeout)
- **Permanent failures**: After 5 retries, messages move to DLQ
- **Comprehensive logging**: All failures logged with full context
- **Metrics**: Success and failure metrics for monitoring

### Observability
- **CloudWatch Logs**: Structured logging with message context
- **Custom Metrics**: `MessagesReceived`, `MessagesProcessedSuccess`, `MessagesProcessedFailure`, `ProcessingDuration`
- **ECS Metrics**: CPU, memory, task health
- **SQS Metrics**: Queue depth, message age, DLQ count

### Scalability
- **Current capacity**: Handles ~7 messages/minute (10k/day) with single task
- **Horizontal scaling**: Auto-scales based on CPU (55-85% target) and queue depth
- **Scale to zero**: All environments scale to 0 tasks when queue is empty for 15 minutes
- **CPU scaling**: Maintains capacity between min and max while messages exist (never scales to 0)
- **Queue scaling**: Only queue-based scaling can reduce to 0 tasks
- **Auto-scaling**: Min 0 tasks, max 10 tasks (dev: 3, beta: 5, prod: 10)
- **Batch processing**: Processes up to 10 messages per poll

## Performance Targets

- **Throughput**: 10,000 messages/day (~7 messages/minute average)
- **Latency**: 99% of messages processed within 15 minutes
- **Availability**: Auto-recovery from failures via ECS task restarts
- **Cost**: ~$36/month for template workload (NAT gateway dominates at ~$33/month)

## Documentation

Comprehensive steering documents in `.kiro/`:

- **PROJECT_OVERVIEW.md**: Architecture, requirements, and design decisions
- **INFRASTRUCTURE.md**: CDK implementation details and AWS resource configuration
- **DEPLOYMENT.md**: Build, deployment, operations, and troubleshooting
- **steering/service-implementation.md**: Kotlin service internals (loaded only for `service/**` queries)

## Technology Stack

- **Service**: Kotlin 2.3+ with AWS SDK for Kotlin
- **Build**: Gradle 9+ with Kotlin DSL (build.gradle.kts)
- **Runtime**: Amazon Corretto 25 JRE on Alpine Linux
- **Infrastructure**: AWS CDK 2.x with TypeScript
- **Hosting**: AWS ECS Fargate (ARM64/Graviton, private subnets)
- **Queue**: AWS SQS with DLQ
- **Observability**: AWS CloudWatch Logs & Metrics
- **Container Registry**: Amazon ECR (private)

## Cost Estimate

For 10,000 messages/day workload:
- ECS Fargate (0.25 vCPU, 0.5 GB):
  - 24/7: ~$9/month
  - With scale-to-zero (avg 2 hours/day): ~$1/month
- SQS requests: < $1/month (first 1M free)
- CloudWatch Logs (30-day retention): $1-2/month
- Container Insights: < $1/month
- ECR storage: $1/month
- NAT Gateway: ~$32/month
- **Total**:
  - Dev with scale-to-zero + NAT: ~$36/month
  - Prod 24/7 + NAT: ~$45/month

## Security

- IAM roles for ECS tasks (no hardcoded credentials)
- Least-privilege IAM policies
- ECR image scanning enabled
- CloudWatch Logs encryption
- SQS encryption at rest (AWS managed keys)

## License

MIT License - see LICENSE file for details
