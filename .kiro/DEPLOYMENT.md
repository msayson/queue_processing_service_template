# Deployment

Deploy with `scripts/deploy.sh --awsAccountId ACCOUNT_ID --awsRegion REGION`.
The script handles CDK bootstrap, stack deploy, Docker build (linux/arm64), ECR push, and ECS force-deploy in order.

## AWS Resources

| Resource | Identifier |
|---|---|
| ECS Cluster | resolved from `QueueProcessingServiceStack` via CloudFormation |
| ECS Service | resolved from `QueueProcessingServiceStack` via CloudFormation |
| ECR Repository | `templates/queue-processing-service` |
| CloudWatch log group | `/ecs/queue-processing-service` |

## Scaling Behavior

Auto-scaling is queue-based (can scale to 0) and CPU-based (cannot scale to 0):
- 0 messages for 15 min → 0 tasks (all envs)
- > 0 messages → min 1 task; > 10 messages → scale out
- Max tasks: dev 3, beta 5, prod 10

Tasks may legitimately be at 0 when the queue is empty. CPU scaling only operates between min/max while tasks are running.

## Monitoring

**Logs**: `aws logs tail /ecs/queue-processing-service --follow`

**Key metrics**:
- `MessagesReceived`, `MessagesProcessedSuccess`, `MessagesProcessedFailure`, `ProcessingDuration` (custom, from service)
- `ApproximateNumberOfMessagesVisible` on main queue and DLQ
- ECS CPU/memory utilization

**Recommended alarms**:
- DLQ `ApproximateNumberOfMessagesVisible` > 0
- `MessagesProcessedFailure` > 10 in 5 minutes
- Main queue depth > 1000

## Troubleshooting

**Service not processing messages**:
1. Check running tasks — may be legitimately scaled to 0: `aws ecs list-tasks --cluster <CLUSTER>`
2. Check CloudWatch logs for errors
3. Verify IAM permissions (SQS receive/delete, CloudWatch put-metric)
4. Verify `QUEUE_URL` env var on the task definition
5. Check auto-scaling policies and queue depth if stuck at 0

**Messages going to DLQ**:
1. Check CloudWatch logs for processing errors
2. Inspect DLQ: `aws sqs receive-message --queue-url <DLQ_URL>`
3. Fix root cause, then redrive via SQS Console (DLQ → "Start DLQ redrive") or manually

**Container crashes**:
1. Check CloudWatch logs for exceptions
2. Check ECS stopped-task reason: `aws ecs describe-tasks --cluster <CLUSTER> --tasks <TASK_ARN>`
3. Verify resource limits (CPU/memory) and that the ARM64 image was built correctly

**High latency / backlog**:
1. Check queue depth
2. Check ECS task CPU/memory utilization
3. Scale manually if auto-scaling hasn't caught up: `aws ecs update-service --cluster <CLUSTER> --service <SERVICE> --desired-count N`

## Cleanup

```bash
cd infrastructure && npx cdk destroy
```
Also manually empty/delete the ECR repository and any CloudWatch log groups or alarms.
