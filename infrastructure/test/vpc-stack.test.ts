import { App } from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { VpcStack } from '../lib/vpc-stack';

describe('VpcStack', () => {
  test('matches snapshot', () => {
    const app = new App();
    const vpcStack = new VpcStack(app, 'TestVpcStack');
    expect(Template.fromStack(vpcStack).toJSON()).toMatchSnapshot();
  });
});
