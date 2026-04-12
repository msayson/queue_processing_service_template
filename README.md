# Queue Processing Service Template

A production-ready template for building AWS-hosted queue processing services in Kotlin. This template provides a complete foundation for services that poll SQS queues, process messages idempotently, handle failures gracefully, and emit comprehensive observability metrics.

## Features

- **Kotlin Service**: Modern, type-safe JVM application using AWS SDK for Kotlin
- **Infrastructure as Code**: Complete AWS CDK setup in TypeScript
- **AWS ECS Fargate**: Serverless container hosting with auto-scaling capabilities
- **SQS Integration**: Long polling, batch processing, automatic retries, and DLQ
- **Observability**: CloudWatch Logs and custom metrics for full visibility
- **Production-Ready**: Error handling, retry logic, idempotency patterns
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

- AWS Account with appropriate permissions
- AWS CLI v2+ configured
- Node.js 24+ (for CDK)
- Docker (for building container images)
- JDK 25+
- Gradle 9+

### 1. Bootstrap CDK (first time only)

```bash
cdk bootstrap aws://ACCOUNT-ID/REGION
```

### 2. Deploy Infrastructure

```bash
cd infrastructure
npm install

# Set environment variables
export AWS_ACCOUNT_ID=123456789012
export AWS_REGION=us-east-1
export ENVIRONMENT=dev
export DEVELOPER_NAME=yourname

cdk deploy
```

This creates:
- VPC configuration (private subnets, NAT Gateway or VPC endpoints)
- SQS queue and dead letter queue
- ECR repository
- ECS cluster, task definition, and service
- Auto-scaling policies (CPU-based and queue-based, scale to 0)
- IAM roles and policies
- CloudWatch log group

### 3. Build and Push Service

```bash
cd service
docker build -t queue-processing-service:latest .

# Authenticate to ECR
aws ecr get-login-password --region REGION | \
  docker login --username AWS --password-stdin ACCOUNT-ID.dkr.ecr.REGION.amazonaws.com

# Tag and push
docker tag queue-processing-service:latest \
  ACCOUNT-ID.dkr.ecr.REGION.amazonaws.com/queue-processing-service:latest
docker push ACCOUNT-ID.dkr.ecr.REGION.amazonaws.com/queue-processing-service:latest
```

### 4. Verify Deployment

```bash
# Check ECS service status
aws ecs describe-services --cluster QueueProcessingCluster --services QueueProcessingService

# View logs
aws logs tail /ecs/queue-processing-service --follow

# Send test message
aws sqs send-message \
  --queue-url $(aws sqs get-queue-url --queue-name QueueProcessingQueue --query 'QueueUrl' --output text) \
  --message-body '{"eventType":"TEST","data":"Hello World"}'
```

## Project Structure

```
queue_processing_service_template/
├── .kiro/                          # Kiro steering documents
│   ├── PROJECT_OVERVIEW.md         # Architecture and requirements
│   ├── INFRASTRUCTURE.md           # CDK implementation guide
│   ├── SERVICE_IMPLEMENTATION.md   # Kotlin service guide
│   └── DEPLOYMENT.md               # Build and deployment guide
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
- **Cost**: ~$12-20/month for template workload

## Documentation

Comprehensive steering documents in `.kiro/`:

- **PROJECT_OVERVIEW.md**: Architecture, requirements, and design decisions
- **INFRASTRUCTURE.md**: CDK implementation details and AWS resource configuration
- **SERVICE_IMPLEMENTATION.md**: Kotlin service architecture and extension points
- **DEPLOYMENT.md**: Build, deployment, operations, and troubleshooting

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
- ECS Fargate (0.5 vCPU, 2 GB):
  - 24/7: $20-30/month
  - With scale-to-zero (avg 2 hours/day): $2-3/month
- SQS requests: < $1/month (first 1M free)
- CloudWatch Logs (7-day retention): $1-2/month
- ECR storage: $1/month
- NAT Gateway: ~$32/month (or use VPC endpoints)
- VPC Endpoints (optional): ~$35/month (5 endpoints)
- **Total**:
  - Dev with scale-to-zero + NAT: ~$36-38/month
  - Prod 24/7 + VPC endpoints: ~$58-70/month

## Security

- IAM roles for ECS tasks (no hardcoded credentials)
- Least-privilege IAM policies
- ECR image scanning enabled
- CloudWatch Logs encryption
- SQS encryption at rest (AWS managed keys)

## License

MIT License - see LICENSE file for details

## Support

This is a template project. For issues or questions:
1. Review steering documents in `.kiro/`
2. Check AWS documentation for service-specific issues
3. Consult AWS SDK for Kotlin documentation

## Contributing

Contributions welcome! This template should remain minimal and focused on core queue processing patterns. Extensions and domain-specific logic belong in projects that use this template.
