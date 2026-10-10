import * as cdk from 'aws-cdk-lib';
import { Template, Match } from 'aws-cdk-lib/assertions';
import { NetworkStack } from '../lib/network-stack';
import { PaymentServiceStack } from '../lib/payment-service-stack';

describe('PaymentServiceStack invariants', () => {
  let template: Template;

  beforeAll(() => {
    const app = new cdk.App();
    const network = new NetworkStack(app, 'TestNetwork');
    const stack = new PaymentServiceStack(app, 'TestService', {
      vpc: network.vpc,
    });
    template = Template.fromStack(stack);
  });

  test('Aurora cluster has storage encryption enabled', () => {
    template.hasResourceProperties('AWS::RDS::DBCluster', {
      StorageEncrypted: true,
    });
  });

  test('All SQS queues have a redrive policy or are themselves DLQs', () => {
    const queues = template.findResources('AWS::SQS::Queue');
    const ids = Object.keys(queues);
    const mainQueues = ids.filter((id) => !id.toLowerCase().includes('dlq'));
    for (const id of mainQueues) {
      expect(queues[id].Properties).toHaveProperty('RedrivePolicy');
    }
  });

  test('All SNS topics enforce TLS-only delivery', () => {
    template.allResourcesProperties('AWS::SNS::TopicPolicy', {
      PolicyDocument: Match.objectLike({
        Statement: Match.arrayWith([
          Match.objectLike({
            Effect: 'Deny',
            Condition: Match.objectLike({
              Bool: { 'aws:SecureTransport': 'false' },
            }),
          }),
        ]),
      }),
    });
  });

  test('ECS service has a deployment circuit breaker enabled', () => {
    template.hasResourceProperties('AWS::ECS::Service', {
      DeploymentConfiguration: Match.objectLike({
        DeploymentCircuitBreaker: {
          Enable: true,
          Rollback: true,
        },
      }),
    });
  });

  // dashboard.ts hardcodes /aws/ecs/payment-service for its Logs Insights widget; if either
  // side drifts the widget points at the wrong group. Assert the stack provisions that name.
  test('App log group name matches the one queried by the dashboard', () => {
    template.hasResourceProperties('AWS::Logs::LogGroup', {
      LogGroupName: '/aws/ecs/payment-service',
    });
  });
});

// The deploy workflow passes -c running=true|false to preserve the service's current
// mode; both must synth, and idle must really mean zero tasks.
describe('PaymentServiceStack running modes', () => {
  const desiredCount = (running?: string) => {
    const app = new cdk.App({ context: running === undefined ? {} : { running } });
    const network = new NetworkStack(app, 'TestNetwork');
    const stack = new PaymentServiceStack(app, 'TestService', { vpc: network.vpc });
    const services = Template.fromStack(stack).findResources('AWS::ECS::Service');
    return Object.values(services).map((s) => s.Properties.DesiredCount);
  };

  test('defaults to one running task', () => {
    expect(desiredCount()).toEqual([1]);
  });

  test('running=true runs one task', () => {
    expect(desiredCount('true')).toEqual([1]);
  });

  test('running=false scales the service to zero tasks', () => {
    expect(desiredCount('false')).toEqual([0]);
  });
});
