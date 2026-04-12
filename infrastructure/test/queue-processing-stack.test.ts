import { App } from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { QueueProcessingServiceStack } from '../lib/queue-processing-stack';
import { VpcStack } from '../lib/vpc-stack';

describe('QueueProcessingServiceStack', () => {
  test('matches snapshot', () => {
    const app = new App();
    const { vpc } = new VpcStack(app, 'TestVpcStack');
    const stack = new QueueProcessingServiceStack(app, 'TestStack', { vpc });
    expect(Template.fromStack(stack).toJSON()).toMatchSnapshot();
  });
});
