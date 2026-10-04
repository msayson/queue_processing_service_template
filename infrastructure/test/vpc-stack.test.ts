import { App } from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { VpcStack } from '../lib/vpc-stack';

describe('VpcStack', () => {
  test('matches snapshot', () => {
    const app = new App();
    const vpcStack = new VpcStack(app, 'TestVpcStack');
    expect(Template.fromStack(vpcStack).toJSON()).toMatchSnapshot();
  });

  test('creates one NAT gateway per AZ by default', () => {
    const app = new App();
    const vpcStack = new VpcStack(app, 'TestVpcStack');
    Template.fromStack(vpcStack).resourceCountIs('AWS::EC2::NatGateway', 2);
  });

  test('creates configured number of NAT gateways', () => {
    const app = new App();
    const vpcStack = new VpcStack(app, 'TestVpcStack', { natGateways: 1 });
    Template.fromStack(vpcStack).resourceCountIs('AWS::EC2::NatGateway', 1);
  });
});
