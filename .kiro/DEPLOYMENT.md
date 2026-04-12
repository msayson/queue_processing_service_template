# Deployment Guide

## Overview
This document covers building, deploying, and operating the queue processing service.

## Prerequisites

### Local Development Tools
> All tool versions are defined in `VERSIONS.md`.

- **Node.js**: See `VERSIONS.md` (for CDK)
- **AWS CDK CLI**: `npm install -g aws-cdk`
- **Docker**: For building container images
- **AWS CLI**: See `VERSIONS.md`, configured with credentials
- **Gradle**: See `VERSIONS.md` (or use wrapper)
- **JDK**: See `VERSIONS.md`

### AWS Account Setup
- AWS account with appropriate permissions
- AWS CLI configured with credentials (`aws configure`)
- CDK bootstrapped in target region: `cdk bootstrap`

## Build Process

### 1. Build Kotlin Service

**Local build**:
```bash
cd service
./gradlew build
```

**Run locally** (requires LocalStack or AWS credentials):
```bash
export QUEUE_URL=https://sqs.us-east-1.amazonaws.com/123456789012/MyQueue
export AWS_REGION=us-east-1
./gradlew run
```

### 2. Build Docker Image

**Build for ARM64** (Graviton):
```bash
cd service

# Build for ARM64 platform
docker buildx build --platform linux/arm64 -t queue-processing-service:latest .

# Or build multi-platform
docker buildx build --platform linux/arm64,linux/amd64 -t queue-processing-service:latest .
```

**Test locally** (requires ARM64 machine or emulation):
```bash
docker run --rm \
  --platform linux/arm64 \
  -e QUEUE_URL=$QUEUE_URL \
  -e AWS_REGION=$AWS_REGION \
  -e AWS_ACCESS_KEY_ID=$AWS_ACCESS_KEY_ID \
  -e AWS_SECRET_ACCESS_KEY=$AWS_SECRET_ACCESS_KEY \
  queue-processing-service:latest
```

**Note**: Building ARM64 images on x86_64 machines requires Docker Buildx with QEMU emulation (slower builds).

### 3. Push to ECR

**Authenticate Docker to ECR**:
```bash
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin \
  123456789012.dkr.ecr.us-east-1.amazonaws.com
```

**Tag and push**:
```bash
docker tag queue-processing-service:latest \
  123456789012.dkr.ecr.us-east-1.amazonaws.com/queue-processing-service:latest

docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/queue-processing-service:latest
```

## Deployment Process

### Initial Deployment

**1. Install CDK dependencies**:
```bash
cd infrastructure
npm install
```

**2. Synthesize CloudFormation template** (optional, for review):
```bash
cdk synth
```

**3. Deploy infrastructure**:
```bash
# Set environment variables for dev deployment
export AWS_ACCOUNT_ID=123456789012
export AWS_REGION=us-east-1
export ENVIRONMENT=dev
export DEVELOPER_NAME=yourname

cdk deploy
```

This creates:
- VPC configuration (private subnets, NAT Gateway or VPC endpoints)
- SQS queue and DLQ
- ECR repository
- ECS cluster, task definition, and service
- Auto-scaling policies (CPU-based and queue-based, scale to 0)
- IAM roles and policies
- CloudWatch log group

**4. Build and push Docker image** (see above)

**5. Update ECS service** (if needed):
```bash
aws ecs update-service \
  --cluster QueueProcessingCluster \
  --service QueueProcessingService \
  --force-new-deployment
```

### Subsequent Deployments

**Infrastructure changes**:
```bash
cd infrastructure
cdk diff    # Preview changes
cdk deploy  # Apply changes
```

**Service code changes**:
```bash
cd service
docker build -t queue-processing-service:latest .
docker tag queue-processing-service:latest \
  123456789012.dkr.ecr.us-east-1.amazonaws.com/queue-processing-service:latest
docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/queue-processing-service:latest

# Force ECS to pull new image
aws ecs update-service \
  --cluster QueueProcessingCluster \
  --service QueueProcessingService \
  --force-new-deployment
```

## CI/CD Integration

### GitHub Actions Example

**Workflow file** (`.github/workflows/deploy.yml`):
```yaml
name: Deploy

on:
  push:
    branches: [main]

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v2
        with:
          aws-access-key-id: ${{ secrets.AWS_ACCESS_KEY_ID }}
          aws-secret-access-key: ${{ secrets.AWS_SECRET_ACCESS_KEY }}
          aws-region: us-east-1

      - name: Login to ECR
        id: ecr-login
        uses: aws-actions/amazon-ecr-login@v1

      - name: Build and push Docker image
        env:
          ECR_REGISTRY: ${{ steps.ecr-login.outputs.registry }}
          ECR_REPOSITORY: queue-processing-service
          IMAGE_TAG: ${{ github.sha }}
        run: |
          cd service
          docker build -t $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG .
          docker tag $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG \
            $ECR_REGISTRY/$ECR_REPOSITORY:latest
          docker push $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG
          docker push $ECR_REGISTRY/$ECR_REPOSITORY:latest

      - name: Deploy CDK stack
        run: |
          cd infrastructure
          npm install
          npx cdk deploy --require-approval never

      - name: Update ECS service
        run: |
          aws ecs update-service \
            --cluster QueueProcessingCluster \
            --service QueueProcessingService \
            --force-new-deployment
```

### GitLab CI Example

**Pipeline file** (`.gitlab-ci.yml`):
```yaml
stages:
  - build
  - deploy

variables:
  AWS_DEFAULT_REGION: us-east-1
  ECR_REPOSITORY: queue-processing-service

build:
  stage: build
  image: docker:latest
  services:
    - docker:dind
  script:
    - apk add --no-cache aws-cli
    - aws ecr get-login-password | docker login --username AWS --password-stdin $ECR_REGISTRY
    - cd service
    - docker build -t $ECR_REGISTRY/$ECR_REPOSITORY:$CI_COMMIT_SHA .
    - docker tag $ECR_REGISTRY/$ECR_REPOSITORY:$CI_COMMIT_SHA $ECR_REGISTRY/$ECR_REPOSITORY:latest
    - docker push $ECR_REGISTRY/$ECR_REPOSITORY:$CI_COMMIT_SHA
    - docker push $ECR_REGISTRY/$ECR_REPOSITORY:latest

deploy:
  stage: deploy
  image: node:18
  script:
    - npm install -g aws-cdk
    - cd infrastructure
    - npm install
    - cdk deploy --require-approval never
    - aws ecs update-service --cluster QueueProcessingCluster --service QueueProcessingService --force-new-deployment
```

## Operations

### Monitoring

**CloudWatch Logs**:
- Log group: `/ecs/queue-processing-service`
- View in AWS Console or use AWS CLI:
```bash
aws logs tail /ecs/queue-processing-service --follow
```

**CloudWatch Metrics**:
- Custom metrics: `MessagesReceived`, `MessagesProcessedSuccess`, `MessagesProcessedFailure`, `ProcessingDuration`
- ECS metrics: CPU utilization, memory utilization
- SQS metrics: `ApproximateNumberOfMessagesVisible`, `NumberOfMessagesSent`, `NumberOfMessagesDeleted`

**Key metrics to monitor**:
1. DLQ message count (should be 0 or very low)
2. Processing failure rate
3. Queue depth (messages waiting)
4. ECS task health

### Alerting

**Recommended CloudWatch Alarms**:

1. **DLQ Messages**:
   - Metric: `ApproximateNumberOfMessagesVisible` on DLQ
   - Threshold: > 0
   - Action: SNS notification

2. **High Failure Rate**:
   - Metric: `MessagesProcessedFailure`
   - Threshold: > 10 in 5 minutes
   - Action: SNS notification

3. **Queue Backlog**:
   - Metric: `ApproximateNumberOfMessagesVisible` on main queue
   - Threshold: > 1000
   - Action: SNS notification (may need scaling)

4. **Cold Start Latency** (with scale-to-zero):
   - Metric: Time from first message to task running
   - Threshold: > 5 minutes
   - Action: SNS notification (investigate scaling issues)

### Scaling

**Manual scaling**:
```bash
aws ecs update-service \
  --cluster QueueProcessingCluster \
  --service QueueProcessingService \
  --desired-count 3
```

**Auto-scaling** (configured in CDK):
- **CPU-based**: Target 55-85% CPU utilization (does NOT scale to 0)
- **Queue-based**: Scale based on `ApproximateNumberOfMessagesVisible`
  - 0 messages for 15 minutes → 0 tasks (all environments)
  - > 0 messages → min 1 task
  - > 10 messages → scale out
- Min tasks: 0 (all environments)
- Max tasks: 10 (dev: 3, beta: 5, prod: 10)
- **Note**: Only queue-based scaling can scale to 0; CPU scaling operates between min and max when tasks are running

**Monitoring auto-scaling**:
```bash
# Check current task count
aws ecs describe-services \
  --cluster QueueProcessingCluster \
  --services QueueProcessingService \
  --query 'services[0].runningCount'

# Check scaling activities
aws application-autoscaling describe-scaling-activities \
  --service-namespace ecs \
  --resource-id service/QueueProcessingCluster/QueueProcessingService
```

### Troubleshooting

**Service not processing messages**:
1. Check if tasks are running (may be scaled to 0): `aws ecs list-tasks --cluster QueueProcessingCluster`
2. Check CloudWatch logs for errors
3. Verify IAM permissions (SQS, CloudWatch)
4. Verify queue URL environment variable is correct
5. If scaled to 0, check auto-scaling policies and queue depth

**Messages going to DLQ**:
1. Check CloudWatch logs for processing errors
2. Inspect DLQ messages: `aws sqs receive-message --queue-url <DLQ_URL>`
3. Identify common failure patterns
4. Fix code and redrive messages from DLQ if needed

**High latency**:
1. Check queue depth (backlog)
2. Check ECS task CPU/memory utilization
3. Consider scaling up (more tasks or larger tasks)
4. Review processing logic for bottlenecks

**Container crashes**:
1. Check CloudWatch logs for exceptions
2. Review ECS task stopped reason
3. Check resource limits (CPU, memory)
4. Verify Docker image builds correctly

### Redriving DLQ Messages

**Manual redrive** (after fixing issues):
```bash
# Receive message from DLQ
aws sqs receive-message --queue-url <DLQ_URL> --max-number-of-messages 10

# Send to main queue
aws sqs send-message --queue-url <MAIN_QUEUE_URL> --message-body "<body>"

# Delete from DLQ
aws sqs delete-message --queue-url <DLQ_URL> --receipt-handle "<handle>"
```

**Automated redrive** (using AWS Console):
- SQS Console → DLQ → "Start DLQ redrive"
- Select destination queue (main queue)
- Configure redrive policy

## Cleanup

**Delete all resources**:
```bash
cd infrastructure
cdk destroy
```

**Manual cleanup** (if needed):
- Empty and delete ECR repository
- Delete CloudWatch log groups
- Delete any custom alarms or dashboards

## Security Considerations

### Secrets Management
- Never commit AWS credentials to Git
- Use IAM roles for ECS tasks (not access keys)
- Store sensitive configuration in AWS Secrets Manager or SSM Parameter Store
- Rotate credentials regularly

### Network Security
- Use private subnets with NAT gateway for production
- Restrict security group rules (only outbound HTTPS)
- Use VPC endpoints for AWS services (cost optimization)
- Enable VPC Flow Logs for network monitoring

### Compliance
- Enable CloudTrail for API audit logging
- Enable AWS Config for resource compliance
- Tag all resources for cost allocation and governance
- Implement least-privilege IAM policies

## Cost Management

**Estimated monthly costs** (10k messages/day):
- ECS Fargate (0.5 vCPU ARM64, 2 GB):
  - 24/7: ~$16-24/month (20% cheaper than x86_64)
  - With scale-to-zero (avg 2 hours/day): ~$2-3/month
- SQS (10k messages): < $1 (first 1M free)
- CloudWatch Logs (7-day retention): $1-2
- ECR storage: $1
- NAT Gateway: ~$32/month (or $0 with VPC endpoints)
- VPC Endpoints (if used): ~$7/month per endpoint × 5 = ~$35/month
- **Total**:
  - Dev with NAT + scale-to-zero: ~$36-38/month
  - Dev with VPC endpoints + scale-to-zero: ~$40-45/month
  - Prod with NAT (24/7): ~$50-59/month
  - Prod with VPC endpoints (24/7): ~$54-63/month

**Cost optimization tips**:
1. Use ARM64 Graviton (20% cheaper, 15-40% faster than x86_64)
2. Use scale-to-zero for dev environments (saves ~90% on compute)
3. Use VPC endpoints instead of NAT Gateway for prod (saves ~$25/month at scale)
4. Use Fargate Spot for non-critical workloads (70% savings on compute)
5. Reduce log retention period for dev (3-7 days)
6. Delete old ECR images with lifecycle policies
7. Share beta/prod accounts across teams to amortize NAT/VPC endpoint costs

## Backup and Disaster Recovery

**SQS**:
- Messages retained for 14 days (no backup needed)
- DLQ captures failed messages

**Infrastructure**:
- CDK code is version-controlled (infrastructure as code)
- Can redeploy to any region or account

**Service code**:
- Version-controlled in Git
- Docker images tagged and stored in ECR

**Recovery procedure**:
1. Redeploy CDK stack: `cdk deploy`
2. Push Docker image to ECR
3. Service automatically starts processing

**RTO/RPO**:
- Recovery Time Objective (RTO): ~10 minutes (redeploy time)
- Recovery Point Objective (RPO): 0 (no data loss, messages in SQS)
