import { CfnOutput, Duration, RemovalPolicy, Stack, StackProps } from 'aws-cdk-lib';
import { SubnetType, Vpc } from 'aws-cdk-lib/aws-ec2';
import { Repository } from 'aws-cdk-lib/aws-ecr';
import { Cluster, ContainerImage, CpuArchitecture, FargateService, FargateTaskDefinition, LogDrivers, OperatingSystemFamily } from 'aws-cdk-lib/aws-ecs';
import { LogGroup, RetentionDays } from 'aws-cdk-lib/aws-logs';
import { Queue, QueueEncryption } from 'aws-cdk-lib/aws-sqs';
import { Construct } from 'constructs';

export interface QueueProcessingServiceStackProps extends StackProps {
  readonly vpc: Vpc;
}

export class QueueProcessingServiceStack extends Stack {
  constructor(scope: Construct, id: string, props: QueueProcessingServiceStackProps) {
    super(scope, id, props);

    const { vpc } = props;

    // --- SQS ---

    const deadLetterQueue = new Queue(this, 'DeadLetterQueue', {
      encryption: QueueEncryption.KMS_MANAGED,
      enforceSSL: true,
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
      visibilityTimeout: Duration.seconds(300),
    });

    // --- ECR ---

    const repository = new Repository(this, 'Repository', {
      emptyOnDelete: true,
      removalPolicy: RemovalPolicy.DESTROY,
      repositoryName: 'templates/queue-processing-service',
    });

    // --- ECS ---

    const cluster = new Cluster(this, 'Cluster', { vpc });

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

    const logGroup = new LogGroup(this, 'LogGroup', {
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
    });

    new FargateService(this, 'Service', {
      cluster,
      taskDefinition,
      desiredCount: 1,
      vpcSubnets: { subnetType: SubnetType.PRIVATE_WITH_EGRESS },
    });

    // --- Outputs ---

    new CfnOutput(this, 'QueueUrl', { value: queue.queueUrl });
    new CfnOutput(this, 'DeadLetterQueueUrl', { value: deadLetterQueue.queueUrl });
    new CfnOutput(this, 'RepositoryUri', { value: repository.repositoryUri });
  }
}
