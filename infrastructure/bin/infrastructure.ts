#!/usr/bin/env node
import { App } from 'aws-cdk-lib';
import { QueueProcessingServiceStack } from '../lib/queue-processing-stack';
import { VpcStack } from '../lib/vpc-stack';

const app = new App();

const env = {
  account: process.env.CDK_DEFAULT_ACCOUNT,
  region: process.env.CDK_DEFAULT_REGION,
};

const { vpc } = new VpcStack(app, 'VpcStack', { env });

new QueueProcessingServiceStack(app, 'QueueProcessingServiceStack', { env, vpc });
