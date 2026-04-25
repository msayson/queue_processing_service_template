import { CfnOutput, Duration, RemovalPolicy, Stack, StackProps } from 'aws-cdk-lib';
import { AdjustmentType, CfnScalingPolicy, ScalableTarget, ServiceNamespace } from 'aws-cdk-lib/aws-applicationautoscaling';
import { MathExpression } from 'aws-cdk-lib/aws-cloudwatch';
import { SubnetType, Vpc } from 'aws-cdk-lib/aws-ec2';
import { Repository } from 'aws-cdk-lib/aws-ecr';
import { Cluster, ContainerImage, ContainerInsights, CpuArchitecture, FargateService, FargateTaskDefinition, LogDrivers, OperatingSystemFamily } from 'aws-cdk-lib/aws-ecs';
import { PolicyStatement } from 'aws-cdk-lib/aws-iam';
import { LogGroup, RetentionDays } from 'aws-cdk-lib/aws-logs';
import { Queue, QueueEncryption } from 'aws-cdk-lib/aws-sqs';
import { Construct } from 'constructs';

export interface QueueProcessingServiceStackProps extends StackProps {
  readonly queueName: string;
  readonly vpc: Vpc;
}

export class QueueProcessingServiceStack extends Stack {
  private static readonly MAX_TASKS = 10;
  private static readonly BACKLOG_PER_TASK = 100;

  constructor(scope: Construct, id: string, props: QueueProcessingServiceStackProps) {
    super(scope, id, props);

    const { vpc } = props;

    // ECR repository is created by deploy.sh before this stack is deployed.
    // Referencing by name avoids the chicken-and-egg problem of CloudFormation
    // waiting for ECS to stabilise before it can mark the stack CREATE_COMPLETE.
    const repository = Repository.fromRepositoryName(
      this, 'Repository', 'templates/queue-processing-service',
    );

    // --- SQS ---

    const deadLetterQueue = new Queue(this, 'DeadLetterQueue', {
      encryption: QueueEncryption.KMS_MANAGED,
      enforceSSL: true,
      queueName: `${props.queueName}-DLQ`,
      retentionPeriod: Duration.days(14),
    });

    // visibilityTimeout must be >= the service's VISIBILITY_TIMEOUT env var (default 300 s)
    const queue = new Queue(this, 'InputQueue', {
      deadLetterQueue: {
        maxReceiveCount: 5,
        queue: deadLetterQueue,
      },
      encryption: QueueEncryption.KMS_MANAGED,
      enforceSSL: true,
      queueName: props.queueName,
      visibilityTimeout: Duration.seconds(300),
    });

    // --- ECS ---

    // Container Insights is required for the RunningTaskCount metric used by
    // backlog-per-task target tracking scaling.
    const cluster = new Cluster(this, 'Cluster', { vpc, containerInsightsV2: ContainerInsights.ENABLED });

    const taskDefinition = new FargateTaskDefinition(this, 'TaskDefinition', {
      cpu: 256,
      memoryLimitMiB: 512,
      // ARM64 matches the production Docker build target (Graviton2)
      runtimePlatform: {
        cpuArchitecture: CpuArchitecture.ARM64,
        operatingSystemFamily: OperatingSystemFamily.LINUX,
      },
    });

    // Grant the task role the three SQS actions the service needs:
    // ReceiveMessage, ChangeMessageVisibility, DeleteMessage (plus GetQueueUrl)
    queue.grantConsumeMessages(taskDefinition.taskRole);

    // Grant the task role permission to publish custom metrics.
    // Scoped to the service namespace to follow least-privilege.
    taskDefinition.taskRole.addToPrincipalPolicy(new PolicyStatement({
      actions: ['cloudwatch:PutMetricData'],
      resources: ['*'],
      conditions: {
        StringEquals: { 'cloudwatch:namespace': 'QueueProcessingService' },
      },
    }));

    const logGroup = new LogGroup(this, 'LogGroup', {
      logGroupName: '/ecs/queue-processing-service',
      retention: RetentionDays.ONE_MONTH,
      removalPolicy: RemovalPolicy.DESTROY,
    });

    taskDefinition.addContainer('Service', {
      image: ContainerImage.fromEcrRepository(repository, 'latest'),
      environment: {
        AWS_REGION: this.region,
        QUEUE_URL: queue.queueUrl,
      },
      logging: LogDrivers.awsLogs({
        streamPrefix: 'queue-processing-service',
        logGroup,
      }),
      // Give the service up to 120 s (Fargate maximum) to finish processing its
      // current message after ECS sends SIGTERM on scale-in before SIGKILL fires.
      stopTimeout: Duration.seconds(120),
    });

    const service = new FargateService(this, 'Service', {
      cluster,
      taskDefinition,
      desiredCount: 0,
      vpcSubnets: { subnetType: SubnetType.PRIVATE_WITH_EGRESS },
    });

    // --- Auto-scaling ---
    //
    // Two policies work together:
    //   ScaleInOnEmptyQueue  — shuts the service back to 0 after 15 idle minutes
    //   BacklogPerTask       — target tracking that wakes the service from 0 and
    //                          sizes the fleet to maintain BACKLOG_PER_TASK messages
    //                          per running task

    // Use ScalableTarget directly (rather than service.autoScaleTaskCount) so
    // that scaleToTrackMetric accepts a MathExpression for backlog-per-task.
    // The ECS wrapper's scaleToTrackCustomMetric only allows direct Metric objects.
    const scaling = new ScalableTarget(this, 'TaskCountTarget', {
      serviceNamespace: ServiceNamespace.ECS,
      scalableDimension: 'ecs:service:DesiredCount',
      resourceId: `service/${cluster.clusterName}/${service.serviceName}`,
      minCapacity: 0,
      maxCapacity: QueueProcessingServiceStack.MAX_TASKS,
    });

    // Idle shutdown: scale to 0 after 15 consecutive minutes with no messages
    // visible OR in-flight. Using only ApproximateNumberOfMessagesVisible would
    // fire while tasks are mid-processing (in-flight messages are invisible).
    const totalMessages = new MathExpression({
      expression: 'visible + notVisible',
      usingMetrics: {
        visible:    queue.metricApproximateNumberOfMessagesVisible({ period: Duration.minutes(1) }),
        notVisible: queue.metricApproximateNumberOfMessagesNotVisible({ period: Duration.minutes(1) }),
      },
      period: Duration.minutes(1),
    });

    scaling.scaleOnMetric('ScaleInOnEmptyQueue', {
      metric: totalMessages,
      scalingSteps: [
        { upper: 1, change: -1000 }, // 0 total messages → floor to 0 (capped at minCapacity)
        { lower: 1, change:     0 }, // 1+ total messages → no change
      ],
      adjustmentType: AdjustmentType.CHANGE_IN_CAPACITY,
      cooldown: Duration.minutes(1),
      evaluationPeriods: 10, // 10 consecutive minutes of idleness before scale down to 0
    });

    // Sizes the fleet to BACKLOG_PER_TASK messages per task, and wakes the
    // service from 0 when messages arrive.
    //
    // Uses CfnScalingPolicy because higher-level constructs do not support MathExpression.
    //
    // activeTasks = MAX(FILL(running, 0), FILL(desired, 0)): Container Insights
    // stops publishing both metrics when no tasks are active, so FILL prevents
    // missing data from suppressing scale-out. MAX of running and desired covers
    // the provisioning window, preventing repeated wake-up signals that overshoot.
    new CfnScalingPolicy(this, 'BacklogPerTaskPolicy', {
      policyName: 'BacklogPerTask',
      policyType: 'TargetTrackingScaling',
      scalingTargetId: scaling.scalableTargetRef.resourceId,
      targetTrackingScalingPolicyConfiguration: {
        targetValue: QueueProcessingServiceStack.BACKLOG_PER_TASK,
        // Longer scale-in cooldown avoids churning tasks during brief queue lulls;
        // the idle shutdown step policy handles the final scale-to-zero.
        scaleInCooldown: Duration.minutes(10).toSeconds(),
        // Allow time for Container Insights to publish DesiredTaskCount to prevent overshoot.
        scaleOutCooldown: Duration.minutes(3).toSeconds(),
        customizedMetricSpecification: {
          metrics: [
            {
              id: 'messages',
              metricStat: {
                metric: {
                  namespace: 'AWS/SQS',
                  metricName: 'ApproximateNumberOfMessagesVisible',
                  dimensions: [{ name: 'QueueName', value: queue.queueName }],
                },
                stat: 'Average',
              },
              returnData: false,
            },
            {
              id: 'running',
              metricStat: {
                metric: {
                  namespace: 'ECS/ContainerInsights',
                  metricName: 'RunningTaskCount',
                  dimensions: [
                    { name: 'ClusterName', value: cluster.clusterName },
                    { name: 'ServiceName', value: service.serviceName },
                  ],
                },
                stat: 'Average',
              },
              returnData: false,
            },
            {
              id: 'desired',
              metricStat: {
                metric: {
                  namespace: 'ECS/ContainerInsights',
                  metricName: 'DesiredTaskCount',
                  dimensions: [
                    { name: 'ClusterName', value: cluster.clusterName },
                    { name: 'ServiceName', value: service.serviceName },
                  ],
                },
                stat: 'Average',
              },
              returnData: false,
            },
            {
              // Use max of running and desired tasks, filled to 0, to prevent a feedback loop
              // where the expression returns the wake-up signal (BACKLOG_PER_TASK + 1)
              // on every evaluation while the service is starting up (desired > 0 but running = 0).
              // Using IF because MAX isn't supported for CloudWatch time series.
              id: 'activeTasks',
              expression: 'IF(FILL(running, 0) > FILL(desired, 0), FILL(running, 0), FILL(desired, 0))',
              returnData: false,
            },
            {
              id: 'backlogPerTask',
              expression: `IF(activeTasks < 1, IF(messages > 0, ${QueueProcessingServiceStack.BACKLOG_PER_TASK + 1}, 0), messages / activeTasks)`,
              returnData: true,
            },
          ],
        },
      },
    });

    // --- Outputs ---

    new CfnOutput(this, 'QueueUrl', { value: queue.queueUrl });
    new CfnOutput(this, 'DeadLetterQueueUrl', { value: deadLetterQueue.queueUrl });
    new CfnOutput(this, 'RepositoryUri', { value: repository.repositoryUri });
  }
}
