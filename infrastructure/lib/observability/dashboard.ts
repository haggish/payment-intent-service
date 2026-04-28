import * as cdk from 'aws-cdk-lib';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as elbv2 from 'aws-cdk-lib/aws-elasticloadbalancingv2';
import { Construct } from 'constructs';

interface DashboardProps {
  service: ecs.BaseService;
  cluster: rds.DatabaseCluster;
  eventsQueue: sqs.Queue;
  dlq: sqs.Queue;
  loadBalancer: elbv2.ApplicationLoadBalancer;
}

const APP_NS = 'Payments/Application';

/**
 * One dashboard organized around four operator questions:
 *   1. Is the system healthy now? (top row)
 *   2. Did the last deploy break anything? (rows 2–3)
 *   3. Why are payments failing? (row 3 — business metrics)
 *   4. Are we trending toward a problem? (rows 4–5)
 *
 * Business outcomes near the top, infrastructure near the bottom.
 */
export function buildDashboard(scope: Construct, props: DashboardProps): cloudwatch.Dashboard {
  const dashboard = new cloudwatch.Dashboard(scope, 'PaymentsDashboard', {
    dashboardName: 'payments-overview',
    defaultInterval: cdk.Duration.minutes(5),
  });

  // ---------- Helper metric factory ----------
  const appMetric = (name: string, statistic = 'Sum') =>
    new cloudwatch.Metric({
      namespace: APP_NS,
      metricName: name,
      statistic,
      period: cdk.Duration.minutes(5),
    });

  // Success rate: (authorized + captured) / confirmed
  const successRate = new cloudwatch.MathExpression({
    expression: '(authorized + captured) / IF(confirmed > 0, confirmed, 1) * 100',
    usingMetrics: {
      authorized: appMetric('payments.authorized'),
      captured: appMetric('payments.captured'),
      confirmed: appMetric('payments.confirmed'),
    },
    period: cdk.Duration.minutes(5),
    label: 'Success rate %',
  });

  // ---------- Row 1: at-a-glance ----------
  dashboard.addWidgets(
    new cloudwatch.SingleValueWidget({
      title: 'Payment success rate (5 min)',
      metrics: [successRate],
      width: 6,
      height: 4,
      sparkline: true,
    }),
    new cloudwatch.SingleValueWidget({
      title: 'API P99 latency',
      metrics: [
        new cloudwatch.Metric({
          namespace: 'AWS/ApplicationELB',
          metricName: 'TargetResponseTime',
          dimensionsMap: { LoadBalancer: props.loadBalancer.loadBalancerFullName },
          statistic: 'p99',
        }),
      ],
      width: 6,
      height: 4,
    }),
    new cloudwatch.SingleValueWidget({
      title: 'Outbox lag (s)',
      metrics: [appMetric('outbox.oldest_unpublished_age_seconds', 'Maximum')],
      width: 6,
      height: 4,
    }),
    new cloudwatch.SingleValueWidget({
      title: 'DLQ depth',
      metrics: [props.dlq.metricApproximateNumberOfMessagesVisible()],
      width: 6,
      height: 4,
    }),
  );

  // ---------- Row 2: API health ----------
  dashboard.addWidgets(
    new cloudwatch.GraphWidget({
      title: 'API requests by status',
      width: 12,
      stacked: true,
      left: [
        new cloudwatch.Metric({
          namespace: 'AWS/ApplicationELB',
          metricName: 'HTTPCode_Target_2XX_Count',
          dimensionsMap: { LoadBalancer: props.loadBalancer.loadBalancerFullName },
          statistic: 'Sum',
          label: '2xx',
        }),
        new cloudwatch.Metric({
          namespace: 'AWS/ApplicationELB',
          metricName: 'HTTPCode_Target_4XX_Count',
          dimensionsMap: { LoadBalancer: props.loadBalancer.loadBalancerFullName },
          statistic: 'Sum',
          label: '4xx',
        }),
        new cloudwatch.Metric({
          namespace: 'AWS/ApplicationELB',
          metricName: 'HTTPCode_Target_5XX_Count',
          dimensionsMap: { LoadBalancer: props.loadBalancer.loadBalancerFullName },
          statistic: 'Sum',
          label: '5xx',
        }),
      ],
    }),
    new cloudwatch.GraphWidget({
      title: 'Latency percentiles',
      width: 12,
      left: [
        new cloudwatch.Metric({
          namespace: 'AWS/ApplicationELB',
          metricName: 'TargetResponseTime',
          dimensionsMap: { LoadBalancer: props.loadBalancer.loadBalancerFullName },
          statistic: 'p50',
          label: 'p50',
        }),
        new cloudwatch.Metric({
          namespace: 'AWS/ApplicationELB',
          metricName: 'TargetResponseTime',
          dimensionsMap: { LoadBalancer: props.loadBalancer.loadBalancerFullName },
          statistic: 'p90',
          label: 'p90',
        }),
        new cloudwatch.Metric({
          namespace: 'AWS/ApplicationELB',
          metricName: 'TargetResponseTime',
          dimensionsMap: { LoadBalancer: props.loadBalancer.loadBalancerFullName },
          statistic: 'p99',
          label: 'p99',
        }),
      ],
      leftAnnotations: [{ value: 0.5, label: 'SLO p99 < 500ms', color: '#d62728' }],
    }),
  );

  // ---------- Row 3: payment business metrics ----------
  dashboard.addWidgets(
    new cloudwatch.GraphWidget({
      title: 'State transitions per minute',
      width: 12,
      stacked: false,
      left: [
        appMetric('payments.created'),
        appMetric('payments.confirmed'),
        appMetric('payments.authorized'),
        appMetric('payments.captured'),
        appMetric('payments.refunded'),
        appMetric('payments.failed'),
      ],
    }),
    new cloudwatch.GraphWidget({
      title: 'Authorization success rate',
      width: 12,
      left: [
        new cloudwatch.MathExpression({
          expression: 'auth / IF(auth + decline + fail > 0, auth + decline + fail, 1) * 100',
          usingMetrics: {
            auth: appMetric('payments.authorized'),
            decline: appMetric('payments.declined'),
            fail: appMetric('payments.failed'),
          },
        }),
      ],
    }),
  );

  // ---------- Row 4: outbox & async ----------
  dashboard.addWidgets(
    new cloudwatch.GraphWidget({
      title: 'Outbox backlog',
      width: 8,
      left: [appMetric('outbox.unpublished_count', 'Maximum')],
      leftAnnotations: [{ value: 100, label: 'Backlog warning', color: '#ff7f0e' }],
    }),
    new cloudwatch.GraphWidget({
      title: 'Oldest unpublished age (s)',
      width: 8,
      left: [appMetric('outbox.oldest_unpublished_age_seconds', 'Maximum')],
      leftAnnotations: [{ value: 60, label: 'SLI threshold', color: '#d62728' }],
    }),
    new cloudwatch.GraphWidget({
      title: 'SQS queue depth',
      width: 8,
      left: [
        props.eventsQueue.metricApproximateNumberOfMessagesVisible(),
        props.dlq.metricApproximateNumberOfMessagesVisible(),
      ],
    }),
  );

  // ---------- Row 5: infrastructure ----------
  dashboard.addWidgets(
    new cloudwatch.GraphWidget({
      title: 'Aurora Serverless ACU',
      width: 8,
      left: [
        new cloudwatch.Metric({
          namespace: 'AWS/RDS',
          metricName: 'ServerlessDatabaseCapacity',
          dimensionsMap: { DBClusterIdentifier: props.cluster.clusterIdentifier },
          statistic: 'Average',
        }),
      ],
      leftAnnotations: [{ value: 1, label: 'Max ACU', color: '#d62728' }],
    }),
    new cloudwatch.GraphWidget({
      title: 'Aurora connections & CPU',
      width: 8,
      left: [
        new cloudwatch.Metric({
          namespace: 'AWS/RDS',
          metricName: 'DatabaseConnections',
          dimensionsMap: { DBClusterIdentifier: props.cluster.clusterIdentifier },
          statistic: 'Average',
        }),
      ],
      right: [
        new cloudwatch.Metric({
          namespace: 'AWS/RDS',
          metricName: 'CPUUtilization',
          dimensionsMap: { DBClusterIdentifier: props.cluster.clusterIdentifier },
          statistic: 'Average',
        }),
      ],
    }),
    new cloudwatch.GraphWidget({
      title: 'Fargate CPU & memory',
      width: 8,
      left: [props.service.metricCpuUtilization(), props.service.metricMemoryUtilization()],
    }),
  );

  // ---------- Row 6: log insights ----------
  dashboard.addWidgets(
    new cloudwatch.LogQueryWidget({
      title: 'Top errors (last hour)',
      logGroupNames: [`/aws/ecs/payment-service`],
      width: 24,
      queryLines: [
        'fields @timestamp, level, message',
        'filter level = "ERROR"',
        'stats count() by message',
        'sort count() desc',
        'limit 10',
      ],
    }),
  );

  return dashboard;
}
