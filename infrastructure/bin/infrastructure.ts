#!/usr/bin/env node
import { App } from 'aws-cdk-lib';
import { QueueProcessingServiceStack } from '../lib/queue-processing-stack';
import { VpcStack } from '../lib/vpc-stack';

const app = new App();

const env = {
  account: process.env.QUEUE_PROCESSING_ACCOUNT,
  region: process.env.QUEUE_PROCESSING_REGION,
};

// Non-prod stages use a single NAT gateway to halve NAT cost
const stage = process.env.STAGE ?? 'dev';

const { vpc } = new VpcStack(app, 'VpcStack', {
  env,
  natGateways: stage === 'prod' ? 2 : 1,
});

new QueueProcessingServiceStack(app, 'QueueProcessingServiceStack', {
  env,
  vpc,
  queueName: process.env.QUEUE_NAME!,
});
