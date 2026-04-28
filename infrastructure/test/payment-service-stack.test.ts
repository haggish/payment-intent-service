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
      appSecurityGroup: network.appSecurityGroup,
      dbSecurityGroup: network.dbSecurityGroup,
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
});
