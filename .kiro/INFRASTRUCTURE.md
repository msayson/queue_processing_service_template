# Infrastructure Implementation Guide

## Overview
This document guides the implementation of AWS infrastructure using CDK with TypeScript. All infrastructure should be defined as code, version-controlled, and deployable via `cdk deploy`.

## CDK Project Setup

> All versions are defined in `VERSIONS.md`. Use those versions when generating code.

### Required Dependencies
```json
{
  "dependencies": {
    "aws-cdk-lib": "<aws-cdk-lib.version>",
    "constructs": "<constructs.version>"
  },
  "devDependencies": {
    "@types/node": "<types-node.version>",
    "typescript": "<typescript.version>",
    "ts-node": "<ts-node.version>"
  }
}
```

### CDK Configuration (cdk.json)
- Use TypeScript for all CDK code
- Enable context lookups for VPC and availability zones
- Set default region and account from environment or explicit configuration

## Stack Architecture

### Main Stack Components

#### 1. VPC Configuration
**Use Default VPC with Private Subnets**:
- Service runs in private subnets (no direct internet access)
- NAT Gateway for outbound internet access (AWS API calls)
- VPC endpoints for cost optimization (optional but recommended):
  - `com.amazonaws.{region}.ecr.api`
  - `com.amazonaws.{region}.ecr.dkr`
  - `com.amazonaws.{region}.s3` (for ECR layers)
  - `com.amazonaws.{region}.logs`
  - `com.amazonaws.{region}.sqs`

**Security**:
- Service not publicly accessible
- Only invoked by SQS messages or AWS account admins
- Security group allows outbound HTTPS only

#### 2. SQS Queues

**Primary Queue**:
- Name: `{ProjectName}Queue`
- Visibility timeout: 5 minutes (300 seconds)
  - Allows message to be retried if processing fails or times out
- Message retention: 14 days (maximum)
- Receive message wait time: 20 seconds (long polling)
- Dead letter queue configuration:
  - Max receive count: 5
  - Target: DLQ (defined below)

**Dead Letter Queue (DLQ)**:
- Name: `{ProjectName}DLQ`
- Visibility timeout: 5 minutes
- Message retention: 14 days
- Purpose: Capture messages that failed 5 times

#### 3. ECR Repository
- Repository name: `{project-name}-service`
- Image tag mutability: Mutable (allows `latest` tag updates)
- Scan on push: Enabled (security best practice)
- Lifecycle policy: Keep last 10 images

#### 4. ECS Cluster
- Launch type: Fargate
- Name: `{ProjectName}Cluster`
- Container Insights: Enabled (for enhanced CloudWatch metrics)

#### 5. ECS Task Definition
**Task Configuration**:
- CPU: 512 (.5 vCPU)
- Memory: 2048 MB (2 GB)
- Network mode: awsvpc (required for Fargate)
- Requires compatibilities: FARGATE
- CPU architecture: ARM64 (Graviton)

**Container Definition**:
- Image: From ECR repository (Amazon Corretto 25 Alpine)
- Logging: CloudWatch Logs
  - Log group: `/ecs/{project-name}-service`
  - Stream prefix: `service`
  - Retention: 7 days (configurable)

**Environment Variables**:
- `QUEUE_URL`: SQS queue URL (from queue construct)
- `AWS_REGION`: Deployment region
- `MAX_MESSAGES`: 10 (batch size for SQS polling)
- `WAIT_TIME_SECONDS`: 20 (long polling)

**IAM Task Role Permissions**:
- `sqs:ReceiveMessage` on primary queue
- `sqs:DeleteMessage` on primary queue
- `sqs:ChangeMessageVisibility` on primary queue
- `sqs:GetQueueAttributes` on primary queue
- `cloudwatch:PutMetricData` for custom metrics
- `logs:CreateLogStream` and `logs:PutLogEvents` for logging

#### 6. ECS Service
- Desired count: 1 (initial; auto-scaling will adjust)
- Launch type: Fargate
- Platform version: LATEST
- CPU architecture: ARM64 (Graviton - 20% cheaper, better performance)
- Network configuration:
  - Subnets: Private subnets only
  - Security group: Allow outbound HTTPS (443) for AWS API calls
  - Assign public IP: No (private subnet with NAT)
- Health check grace period: 60 seconds
- Deployment configuration:
  - Maximum percent: 200
  - Minimum healthy percent: 100
  - Rolling updates

#### 7. Auto Scaling Configuration
**Target Tracking Scaling**:
- **CPU-based scaling**:
  - Target: 55-85% CPU utilization
  - Scale out: When CPU > 85% for 2 minutes
  - Scale in: When CPU < 55% for 5 minutes
  - Cooldown: 60 seconds scale out, 300 seconds scale in
  - **Does NOT scale to 0** (only scales between min and max capacity)

- **Queue-based scaling**:
  - Metric: `ApproximateNumberOfMessagesVisible`
  - Target: 0 messages → 0 tasks (scale to zero)
  - Target: > 0 messages → min 1 task
  - Target: > 10 messages → scale out based on backlog
  - Min capacity: 0 tasks (all environments)
  - Max capacity: 10 tasks (dev: 3, beta: 5, prod: 10)

**Scale to Zero Logic**:
- When queue is empty for 15 minutes, scale to 0 tasks (all environments)
- First message in queue triggers scale-out (cold start ~2-3 minutes)
- CPU-based scaling only operates when task count > 0
- Queue-based scaling takes precedence for scale-to-zero decisions
- Use CloudWatch alarm to monitor queue depth and trigger scaling

#### 8. CloudWatch Log Group
- Name: `/ecs/{project-name}-service`
- Retention: 7 days (template default; extend for production)
- Encryption: Default (can add KMS key for production)

#### 9. CloudWatch Alarms (Optional for Template)
Projects extending this should add:
- DLQ message count alarm (alert when messages enter DLQ)
- Service CPU/Memory utilization alarms
- Processing error rate alarm

## CDK Stack Structure

### Recommended Organization
```
infrastructure/
├── bin/
│   └── app.ts                    # CDK app entry point
├── lib/
│   ├── queue-processing-stack.ts # Main stack
│   ├── constructs/
│   │   ├── queue-construct.ts    # SQS queue + DLQ
│   │   ├── service-construct.ts  # ECS service + task
│   │   └── repository-construct.ts # ECR repository
│   └── config/
│       └── environment.ts        # Environment-specific config
```

### Multi-Environment Configuration

**Environment Variables**:
- `CDK_DEFAULT_ACCOUNT` or `AWS_ACCOUNT_ID`: Target AWS account ID (required)
- `CDK_DEFAULT_REGION` or `AWS_REGION`: Target region (default: us-east-1)
- `ENVIRONMENT`: Environment name (dev, beta, prod)

**Environment-Specific Settings** (config/environment.ts):
```typescript
interface EnvironmentConfig {
  account: string;
  region: string;
  environment: 'dev' | 'beta' | 'prod';

  // Environment-specific overrides
  logRetentionDays: number;      // dev: 7, beta: 14, prod: 30
  enableVpcEndpoints: boolean;   // dev: false, beta/prod: true
  minCapacity: number;           // All: 0 (scale to zero)
  maxCapacity: number;           // dev: 3, beta: 5, prod: 10
  enableAlarms: boolean;         // dev: false, beta/prod: true
}
```

**Stack Naming Convention**:
- Dev: `QueueProcessing-dev-{developerName}`
- Beta: `QueueProcessing-beta`
- Prod: `QueueProcessing-prod`

**Resource Naming**:
- Include environment in resource names: `{ProjectName}-{Environment}-{Resource}`
- Example: `QueueProcessingService-dev-john-Queue`

### Stack Synthesis
- Use default synthesis (CloudFormation)
- Enable termination protection for production stacks
- Tag all resources with:
  - `Project`: Project name
  - `ManagedBy`: "CDK"
  - `Environment`: dev/staging/prod

## Deployment Considerations

### Bootstrap
First-time CDK users must bootstrap their AWS environment:
```bash
cdk bootstrap aws://ACCOUNT-ID/REGION
```

### Deployment Order
1. Deploy infrastructure stack (creates all resources)
2. Build and push Docker image to ECR
3. Update ECS service to use new image (automatic or manual)

### Environment Variables
Use CDK context or environment variables for:
- AWS account ID (required: `CDK_DEFAULT_ACCOUNT` or `AWS_ACCOUNT_ID`)
- AWS region (default: us-east-1)
- Environment name (dev, beta, prod)
- Developer name (for dev environments)

**Example dev deployment**:
```bash
export AWS_ACCOUNT_ID=123456789012
export AWS_REGION=us-east-1
export ENVIRONMENT=dev
export DEVELOPER_NAME=john
cdk deploy
```

**Example beta/prod deployment**:
```bash
export AWS_ACCOUNT_ID=987654321098
export AWS_REGION=us-east-1
export ENVIRONMENT=prod
cdk deploy
```

### Outputs
Export these stack outputs for reference:
- Queue URL
- Queue ARN
- DLQ URL
- ECR repository URI
- ECS cluster name
- ECS service name

## Security Best Practices
1. Use least-privilege IAM policies
2. Enable encryption at rest for SQS (use AWS managed keys for template)
3. Enable ECR image scanning
4. Use VPC endpoints for AWS services in production (cost optimization)
5. Never hardcode credentials or secrets
6. Use AWS Secrets Manager or SSM Parameter Store for sensitive configuration

## Cost Optimization
For a template processing ~10k messages/day:
- 1 Fargate task (0.5 vCPU ARM64, 2 GB): ~$16-24/month (if running 24/7)
  - ARM64 is 20% cheaper than x86_64
- **With scale-to-zero**: ~$4-8/month (only runs when processing)
- SQS requests: Negligible (first 1M requests free)
- CloudWatch Logs: ~$1-2/month (7-day retention)
- ECR storage: ~$1/month (10 images)
- NAT Gateway: ~$32/month (or $0 with VPC endpoints)
- **Total estimated cost**:
  - With NAT Gateway: ~$37-43/month (24/7) or ~$38-43/month (scale-to-zero)
  - With VPC endpoints: ~$42-50/month (24/7) or ~$43-48/month (scale-to-zero)
  - Dev with scale-to-zero + NAT: ~$37-43/month
  
**Note**: ARM64 Graviton provides 20% cost savings and 15-40% better performance compared to x86_64.

## Testing Infrastructure
- Use `cdk synth` to generate CloudFormation templates
- Use `cdk diff` to preview changes before deployment
- Consider using CDK assertions for unit testing stacks
- Test in a separate AWS account or isolated environment first
