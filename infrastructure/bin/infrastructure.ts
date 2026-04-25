#!/usr/bin/env node
import { App } from 'aws-cdk-lib';
import { QueueProcessingServiceStack } from '../lib/queue-processing-stack';
import { VpcStack } from '../lib/vpc-stack';

const app = new App();

const env = {
  account: process.env.QUEUE_PROCESSING_ACCOUNT,
  region: process.env.QUEUE_PROCESSING_REGION,
};

const { vpc } = new VpcStack(app, 'VpcStack', { env });

new QueueProcessingServiceStack(app, 'QueueProcessingServiceStack', { env, vpc });
