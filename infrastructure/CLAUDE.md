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

**`VpcStack`** (`lib/vpc-stack.ts`, deployed as `QueueProcessorVpcStack` to avoid name clashes with other services) — networking only; exports `vpc`.
- 2 AZs; public subnets hold the NAT gateways, private subnets (`PRIVATE_WITH_EGRESS`, `/24` each) hold ECS tasks
- `natGateways` prop (default 2, one per AZ, so egress survives an AZ failure); `bin/infrastructure.ts` sets 2 when `STAGE=prod`, else 1 (`STAGE` defaults to `dev`) to halve NAT cost
- NAT gateways (in public subnets) — provide outbound internet/cross-account egress; ECS tasks have no public IPs so they are unreachable from the internet
- VPC gateway endpoint: S3 (free; ECR stores image layers in S3)

**`QueueProcessingServiceStack`** (`lib/queue-processing-stack.ts`) — consumes `vpc` via props; deployed after the image push.
- **ECR repository** — referenced by name via `Repository.fromRepositoryName()`; created by `scripts/deploy.sh` before this stack is deployed so ECS can pull the image immediately on creation (avoids `CannotPullContainerError`)
- **SQS input queue** — 300 s visibility timeout, KMS-managed encryption, SSL enforced; routes to DLQ after 5 failed receives
- **SQS dead-letter queue** — 14-day retention, KMS-managed encryption, SSL enforced
- **ECS Fargate** — ARM64 task (256 CPU / 512 MiB), desired count 0 (auto-scaling manages the count), private subnets; env vars `QUEUE_URL`, `AWS_REGION`, `NUM_WORKER_THREADS=4`, `WORKER_SHUTDOWN_GRACE_PERIOD_SECONDS=90` (must stay below container `stopTimeout` of 120 s)
- **Auto-scaling** — min 0 / max 10 tasks (`MAX_TASKS`); backlog-per-task target tracking (target 100 messages/task, `BACKLOG_PER_TASK`; scale-out cooldown 3 min, scale-in cooldown 10 min) also wakes the service from 0 (emits `BACKLOG_PER_TASK + 1` when no tasks are active and messages exist); idle shutdown step policy scales to 0 after 10 consecutive min with no visible or in-flight messages; Container Insights enabled on cluster to supply running/desired task counts
- **IAM** — `queue.grantConsumeMessages(taskRole)` grants `sqs:ReceiveMessage`, `sqs:ChangeMessageVisibility`, `sqs:DeleteMessage`, `sqs:GetQueueUrl`; `cloudwatch:PutMetricData` scoped to the `QueueProcessingService` namespace
- **CloudWatch log group** — 30-day retention

Stack outputs: `QueueUrl`, `DeadLetterQueueUrl`, `RepositoryUri`.

Push a Docker image to the ECR repository tagged `latest` before running `cdk deploy`, or the Fargate task will fail to start.
