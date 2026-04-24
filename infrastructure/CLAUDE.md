# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this directory.

## Commands

```bash
npm install

npm test               # run snapshot tests
npm run test:update    # regenerate snapshots after intentional stack changes

cdk synth      # preview synthesized CloudFormation
cdk diff       # diff against deployed stack
cdk deploy     # deploy to AWS

cdk bootstrap  # one-time setup per account/region
```

## Stacks

**`VpcStack`** (`lib/vpc-stack.ts`) — deployed first; exports `vpc` to the service stack.
- Fully private VPC: 2 AZs, isolated private subnets (`/24` each), no internet gateway, no NAT gateway
- VPC interface endpoints: ECR API, ECR Docker, CloudWatch Logs, SQS, STS
- VPC gateway endpoint: S3 (free; ECR stores image layers in S3)
- Fargate tasks have no internet egress; all AWS API calls route through the VPC endpoints

**`QueueProcessingServiceStack`** (`lib/queue-processing-stack.ts`) — consumes `vpc` via props.
- **SQS input queue** — 300 s visibility timeout, KMS-managed encryption, SSL enforced; routes to DLQ after 5 failed receives
- **SQS dead-letter queue** — 14-day retention, KMS-managed encryption, SSL enforced
- **ECR repository** — holds the service Docker image (`latest` tag)
- **ECS Fargate** — ARM64 task (256 CPU / 512 MiB), desired count 1, private subnets; `QUEUE_URL` and `AWS_REGION` injected as environment variables
- **IAM** — `queue.grantConsumeMessages(taskRole)` grants `sqs:ReceiveMessage`, `sqs:ChangeMessageVisibility`, `sqs:DeleteMessage`, `sqs:GetQueueUrl`
- **CloudWatch log group** — 30-day retention

Stack outputs: `QueueUrl`, `DeadLetterQueueUrl`, `RepositoryUri`.

Push a Docker image to the ECR repository tagged `latest` before running `cdk deploy`, or the Fargate task will fail to start.
