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

  // The default target-group check (GET /) returns 404 from the app, which made every task
  // unhealthy and tripped the deployment circuit breaker.
  test('Load balancer health check targets the actuator readiness probe', () => {
    template.hasResourceProperties('AWS::ElasticLoadBalancingV2::TargetGroup', {
      HealthCheckPath: '/actuator/health/readiness',
      Matcher: { HttpCode: '200' },
    });
  });

  // Startup (~110s) plus the time to pass the healthy threshold must fit in the grace period,
  // or ECS replaces healthy-but-slow tasks.
  test('A started task can turn healthy within the grace period', () => {
    const STARTUP_SECONDS = 110;
    const service = Object.values(template.findResources('AWS::ECS::Service'))[0].Properties;
    const tg = Object.values(
      template.findResources('AWS::ElasticLoadBalancingV2::TargetGroup'),
    )[0].Properties;
    const toHealthy = tg.HealthCheckIntervalSeconds * tg.HealthyThresholdCount;
    expect(STARTUP_SECONDS + toHealthy).toBeLessThan(service.HealthCheckGracePeriodSeconds);
  });

  test('ECS service waits for the database writer instance', () => {
    const [serviceId] = Object.keys(template.findResources('AWS::ECS::Service'));
    const [writerId] = Object.keys(template.findResources('AWS::RDS::DBInstance'));
    const dependsOn: string[] = template.toJSON().Resources[serviceId].DependsOn ?? [];
    expect(dependsOn).toContain(writerId);
  });

  // Boot takes ~80-110s to start on 0.25 vCPU; a shorter grace period kills tasks mid-startup.
  test('ECS service allows the app enough time to start', () => {
    const services = Object.values(template.findResources('AWS::ECS::Service'));
    expect(services).toHaveLength(1);
    expect(services[0].Properties.HealthCheckGracePeriodSeconds).toBeGreaterThanOrEqual(240);
  });

  // dashboard.ts hardcodes /aws/ecs/payment-service for its Logs Insights widget; if either
  // side drifts the widget points at the wrong group. Assert the stack provisions that name.
  test('App log group name matches the one queried by the dashboard', () => {
    template.hasResourceProperties('AWS::Logs::LogGroup', {
      LogGroupName: '/aws/ecs/payment-service',
    });
  });
});

// The app's CloudWatchMeterRegistry publishes counters as '<name>.count' and gauges as
// '<name>.value' (pinned by ObservabilityConfigTest on the Java side). A dashboard widget or
// alarm on a bare 'payments.created' would silently show no data forever.
describe('Application metrics match what the app publishes', () => {
  const app = new cdk.App();
  const network = new NetworkStack(app, 'TestNetwork');
  const stack = new PaymentServiceStack(app, 'TestService', { vpc: network.vpc });
  const template = Template.fromStack(stack);
  const APP_NS = 'Payments/Application';
  const published = /^(payments|outbox)\.[a-z_]+\.(count|value)$/;

  test('alarms read published metric names', () => {
    const names: string[] = [];
    for (const alarm of Object.values(template.findResources('AWS::CloudWatch::Alarm'))) {
      const p = alarm.Properties;
      if (p.Namespace === APP_NS) names.push(p.MetricName);
      for (const m of p.Metrics ?? []) {
        const metric = m.MetricStat?.Metric;
        if (metric?.Namespace === APP_NS) names.push(metric.MetricName);
      }
    }
    expect(names.length).toBeGreaterThan(0);
    for (const name of names) expect(name).toMatch(published);
  });

  test('dashboard widgets read published metric names', () => {
    const body = JSON.stringify(template.findResources('AWS::CloudWatch::Dashboard'));
    const names = [...body.matchAll(/Payments\/Application\\+",\\+"([^"\\]+)/g)].map((m) => m[1]);
    expect(names.length).toBeGreaterThan(0);
    for (const name of names) expect(name).toMatch(published);
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
