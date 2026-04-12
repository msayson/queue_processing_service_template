# Queue Processing Service Template

## Purpose
A production-ready template for building AWS-hosted queue processing services in Kotlin. This template demonstrates best practices for polling SQS queues, processing messages idempotently, handling failures gracefully, and emitting observability metrics.

## Architecture Overview

### Components
1. **Kotlin Service** - ECS Fargate-hosted application that polls SQS and processes messages
2. **AWS CDK Infrastructure** - TypeScript-based infrastructure as code defining all AWS resources
3. **SQS Queue** - Primary queue for incoming messages
4. **Dead Letter Queue (DLQ)** - Captures messages that fail after 5 retry attempts
5. **CloudWatch** - Centralized logging and metrics

### Message Flow
```
Message → SQS Queue → ECS Service polls → Process → Success/Failure
                                              ↓ (failure)
                                         Retry (5x max)
                                              ↓ (exhausted)
                                         Dead Letter Queue
```

## Key Requirements

### Functional
- Poll SQS queue continuously for messages
- Process messages idempotently (safe to retry)
- Log received messages to CloudWatch
- Emit CloudWatch metrics for processing outcomes
- Retry failed messages up to 5 times with visibility timeout
- Route exhausted retries to DLQ

### Non-Functional
- **Throughput**: 10,000 messages/day (~7 messages/minute average)
- **Latency**: 99% of messages processed within 15 minutes
- **Availability**: Service should auto-recover from failures
- **Observability**: Full CloudWatch logging and metrics
- **Cost Optimization**: Scale to 0 tasks when queue is empty (all environments), target 55-85% CPU utilization
- **Security**: Service in private subnet, not publicly accessible

### Multi-Environment Support
- **Environments**: dev-specific (per developer), beta (shared), prod (production)
- **Account Isolation**: Each environment deploys to separate AWS accounts
- **Configuration**: CDK code parameterized by AWS account ID (from environment variable)
- **Initial Setup**: Optimized for dev-specific environments

### Template Nature
This is a **template project**. The actual business logic (e.g., data deletion workflows) should be implemented by projects that clone or extend this template. The template provides:
- Infrastructure scaffolding
- Message polling and error handling patterns
- Observability integration
- Retry and DLQ logic

## Technology Stack
- **Service Language**: Kotlin (JVM)
- **AWS SDK**: AWS SDK for Kotlin (latest official version)
- **Build Tool**: Gradle with Kotlin DSL (build.gradle.kts)
- **Infrastructure**: AWS CDK with TypeScript
- **Container Platform**: AWS ECS Fargate (ARM64/Graviton)
- **Message Queue**: AWS SQS
- **Observability**: AWS CloudWatch Logs & Metrics
- **Container Registry**: Amazon ECR (private)
- **Runtime**: Amazon Corretto 25 JDK on Alpine Linux

## Project Structure
```
queue_processing_service_template/
├── .kiro/                          # Kiro steering documents
│   ├── PROJECT_OVERVIEW.md         # This file
│   ├── INFRASTRUCTURE.md           # CDK infrastructure guidance
│   ├── SERVICE_IMPLEMENTATION.md   # Kotlin service guidance
│   └── DEPLOYMENT.md               # Build and deployment guidance
├── infrastructure/                 # AWS CDK code (TypeScript)
│   ├── bin/                        # CDK app entry point
│   ├── lib/                        # CDK stack definitions
│   ├── cdk.json                    # CDK configuration
│   ├── package.json                # Node dependencies
│   └── tsconfig.json               # TypeScript configuration
├── service/                        # Kotlin service code
│   ├── src/
│   │   ├── main/kotlin/            # Application code
│   │   └── test/kotlin/            # Tests
│   ├── build.gradle.kts            # Gradle build configuration
│   └── Dockerfile                  # Container image definition
├── .gitignore
├── LICENSE
└── README.md                       # User-facing documentation
```

## Development Workflow
1. Define infrastructure in `infrastructure/` using CDK
2. Implement service logic in `service/src/main/kotlin/`
3. Build Docker image locally for testing
4. Deploy infrastructure with `cdk deploy`
5. Service automatically pulls from ECR and runs in Fargate

## Extensibility Points
Projects extending this template should:
1. Replace the placeholder message processing logic in the Kotlin service
2. Add required AWS resources (databases, S3 buckets, etc.) to CDK stacks
3. Update IAM permissions for additional AWS service access
4. Customize CloudWatch metrics and alarms for business-specific KPIs
5. Implement domain-specific error handling and validation
