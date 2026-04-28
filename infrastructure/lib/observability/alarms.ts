import * as cdk from 'aws-cdk-lib';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
import * as actions from 'aws-cdk-lib/aws-cloudwatch-actions';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import { Construct } from 'constructs';

interface AlarmsProps {
  cluster: rds.DatabaseCluster;
  eventsQueue: sqs.Queue;
  dlq: sqs.Queue;
  service: ecs.IService;
  pagingTopic: sns.ITopic;
  notifyTopic: sns.ITopic;
}

const APP_NS = 'Payments/Application';

/**
 * Alarms split into two SNS topics by severity:
 *   - PAGING: system is down for users; wake someone up
 *   - NOTIFY: degradation or capacity warnings; daytime attention
 *
 * The composite alarm at the end captures "the system is meaningfully broken" — multiple
 * conditions simultaneously. Crying-wolf alerting destroys on-call effectiveness, so the
 * paging composite is intentionally conservative.
 */
export function buildAlarms(scope: Construct, props: AlarmsProps): void {
  // ---------- Paging alarms ----------
  const dlqNonEmpty = new cloudwatch.Alarm(scope, 'DlqNonEmpty', {
    alarmName: 'payments-dlq-non-empty',
    metric: props.dlq.metricApproximateNumberOfMessagesVisible(),
    threshold: 0,
    comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
    evaluationPeriods: 1,
    treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
  });
  dlqNonEmpty.addAlarmAction(new actions.SnsAction(props.pagingTopic));

  const successRateLow = new cloudwatch.Alarm(scope, 'SuccessRateLow', {
    alarmName: 'payments-success-rate-low',
    metric: new cloudwatch.MathExpression({
      expression: '(authorized + captured) / IF(confirmed > 0, confirmed, 1) * 100',
      usingMetrics: {
        authorized: appMetric('payments.authorized'),
        captured: appMetric('payments.captured'),
        confirmed: appMetric('payments.confirmed'),
      },
    }),
    threshold: 90,
    comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
    evaluationPeriods: 2,
    datapointsToAlarm: 2,
  });

  const outboxLagHigh = new cloudwatch.Alarm(scope, 'OutboxLagHigh', {
    alarmName: 'payments-outbox-lag-high',
    metric: appMetric('outbox.oldest_unpublished_age_seconds', 'Maximum'),
    threshold: 60,
    comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
    evaluationPeriods: 2,
    datapointsToAlarm: 2,
    treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
  });

  // Composite alarm for true "system is broken" — only this one wakes someone up
  new cloudwatch.CompositeAlarm(scope, 'SystemDown', {
    compositeAlarmName: 'payments-system-down',
    alarmRule: cloudwatch.AlarmRule.anyOf(
      cloudwatch.AlarmRule.allOf(
        cloudwatch.AlarmRule.fromAlarm(successRateLow, cloudwatch.AlarmState.ALARM),
        cloudwatch.AlarmRule.fromAlarm(outboxLagHigh, cloudwatch.AlarmState.ALARM),
      ),
      cloudwatch.AlarmRule.fromAlarm(dlqNonEmpty, cloudwatch.AlarmState.ALARM),
    ),
    actionsEnabled: true,
  }).addAlarmAction(new actions.SnsAction(props.pagingTopic));

  // ---------- Notify alarms ----------
  new cloudwatch.Alarm(scope, 'AuroraCpuHigh', {
    alarmName: 'payments-aurora-cpu-high',
    metric: new cloudwatch.Metric({
      namespace: 'AWS/RDS',
      metricName: 'CPUUtilization',
      dimensionsMap: { DBClusterIdentifier: props.cluster.clusterIdentifier },
      statistic: 'Average',
    }),
    threshold: 85,
    evaluationPeriods: 3,
    datapointsToAlarm: 3,
  }).addAlarmAction(new actions.SnsAction(props.notifyTopic));

  new cloudwatch.Alarm(scope, 'AuroraDeadlocks', {
    alarmName: 'payments-aurora-deadlocks',
    metric: new cloudwatch.Metric({
      namespace: 'AWS/RDS',
      metricName: 'Deadlocks',
      dimensionsMap: { DBClusterIdentifier: props.cluster.clusterIdentifier },
      statistic: 'Sum',
    }),
    threshold: 0,
    evaluationPeriods: 1,
    treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
  }).addAlarmAction(new actions.SnsAction(props.notifyTopic));

  new cloudwatch.Alarm(scope, 'OutboxBacklog', {
    alarmName: 'payments-outbox-backlog',
    metric: appMetric('outbox.unpublished_count', 'Maximum'),
    threshold: 500,
    evaluationPeriods: 2,
    datapointsToAlarm: 2,
    treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
  }).addAlarmAction(new actions.SnsAction(props.notifyTopic));
}

function appMetric(name: string, statistic = 'Sum'): cloudwatch.Metric {
  return new cloudwatch.Metric({
    namespace: APP_NS,
    metricName: name,
    statistic,
    period: cdk.Duration.minutes(5),
  });
}
