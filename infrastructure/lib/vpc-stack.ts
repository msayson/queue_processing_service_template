import { Stack, StackProps } from 'aws-cdk-lib';
import {
  GatewayVpcEndpointAwsService,
  SubnetType,
  Vpc,
} from 'aws-cdk-lib/aws-ec2';
import { Construct } from 'constructs';

export interface VpcStackProps extends StackProps {
  // One NAT gateway per AZ (default) keeps egress available if an AZ fails.
  // A single NAT gateway halves cost but becomes a single point of failure.
  readonly natGateways?: number;
}

export class VpcStack extends Stack {
  private static readonly MAX_AZS = 2;

  readonly vpc: Vpc;

  constructor(scope: Construct, id: string, props?: VpcStackProps) {
    super(scope, id, props);

    this.vpc = new Vpc(this, 'Vpc', {
      maxAzs: VpcStack.MAX_AZS,
      natGateways: props?.natGateways ?? VpcStack.MAX_AZS,
      subnetConfiguration: [
        {
          // NAT gateways live here; ECS tasks are NOT placed in public subnets
          name: 'public',
          subnetType: SubnetType.PUBLIC,
          cidrMask: 24,
        },
        {
          // ECS tasks run here: outbound internet via NAT, no inbound from internet
          name: 'private',
          subnetType: SubnetType.PRIVATE_WITH_EGRESS,
          cidrMask: 24,
        },
      ],
    });

    // S3 gateway endpoint (free) — ECR stores image layers in S3
    this.vpc.addGatewayEndpoint('S3Endpoint', {
      service: GatewayVpcEndpointAwsService.S3,
    });
  }
}
