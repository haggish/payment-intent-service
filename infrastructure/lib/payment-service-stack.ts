import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as ecs from 'aws-cdk-lib/aws-ecs';
import * as ecs_patterns from 'aws-cdk-lib/aws-ecs-patterns';
import * as rds from 'aws-cdk-lib/aws-rds';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as ecr_assets from 'aws-cdk-lib/aws-ecr-assets';
import * as logs from 'aws-cdk-lib/aws-logs';
import { Construct } from 'constructs';
import * as path from 'path';
import { buildDashboard } from './observability/dashboard';
import { buildAlarms } from './observability/alarms';

interface PaymentServiceStackProps extends cdk.StackProps {
  vpc: ec2.IVpc;
}

export class PaymentServiceStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: PaymentServiceStackProps) {
    super(scope, id, props);

    // ---------- Security groups ----------
    // Owned by this stack so SG-to-SG rules (ALB → app, app → DB) stay intra-stack
    // and don't form a cycle with the network stack.
    const appSg = new ec2.SecurityGroup(this, 'AppSg', {
      vpc: props.vpc,
      description: 'Security group for the Fargate app tasks',
      allowAllOutbound: true,
    });

    const dbSg = new ec2.SecurityGroup(this, 'DbSg', {
      vpc: props.vpc,
      description: 'Security group for the Aurora cluster',
      allowAllOutbound: false,
    });

    dbSg.addIngressRule(appSg, ec2.Port.tcp(5432), 'Postgres from app tasks');

    // ---------- Database ----------
    const dbCluster = new rds.DatabaseCluster(this, 'Aurora', {
      engine: rds.DatabaseClusterEngine.auroraPostgres({
        version: rds.AuroraPostgresEngineVersion.VER_16_4,
      }),
      writer: rds.ClusterInstance.serverlessV2('Writer', {
        publiclyAccessible: false,
      }),
      // Scale-to-zero — the cost saver
      serverlessV2MinCapacity: 0,
      serverlessV2MaxCapacity: 1,
      vpc: props.vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      securityGroups: [dbSg],
      credentials: rds.Credentials.fromGeneratedSecret('payments_admin'),
      defaultDatabaseName: 'payments',
      storageEncrypted: true,
      backup: { retention: cdk.Duration.days(1) },
      deletionProtection: false, // showcase setting; flip in production
      removalPolicy: cdk.RemovalPolicy.DESTROY, // showcase setting
    });

    // ---------- Async pipeline ----------
    const dlq = new sqs.Queue(this, 'PaymentEventsDlq', {
      queueName: 'payment-events-dlq.fifo',
      fifo: true,
      retentionPeriod: cdk.Duration.days(14),
      enforceSSL: true,
    });

    const eventsQueue = new sqs.Queue(this, 'PaymentEventsQueue', {
      queueName: 'payment-events.fifo',
      fifo: true,
      contentBasedDeduplication: false, // we set MessageDeduplicationId explicitly
      visibilityTimeout: cdk.Duration.seconds(60),
      enforceSSL: true,
      deadLetterQueue: { queue: dlq, maxReceiveCount: 3 },
    });

    // ---------- Compute ----------
    const cluster = new ecs.Cluster(this, 'Cluster', { vpc: props.vpc });

    const appImage = new ecr_assets.DockerImageAsset(this, 'AppImage', {
      directory: path.join(__dirname, '..', '..'),
      file: 'app/Dockerfile',
    });

    const fargate = new ecs_patterns.ApplicationLoadBalancedFargateService(this, 'Service', {
      cluster,
      cpu: 256,
      memoryLimitMiB: 512,
      desiredCount: this.node.tryGetContext('running') === 'false' ? 0 : 1,
      minHealthyPercent: 100,
      maxHealthyPercent: 200,
      taskImageOptions: {
        image: ecs.ContainerImage.fromDockerImageAsset(appImage),
        containerPort: 8080,
        environment: {
          OUTBOX_QUEUE_URL: eventsQueue.queueUrl,
          SPRING_PROFILES_ACTIVE: 'prod',
          RECONCILIATION_ENABLED: 'true',
        },
        secrets: {
          SPRING_DATASOURCE_USERNAME: ecs.Secret.fromSecretsManager(dbCluster.secret!, 'username'),
          SPRING_DATASOURCE_PASSWORD: ecs.Secret.fromSecretsManager(dbCluster.secret!, 'password'),
          SPRING_DATASOURCE_HOST: ecs.Secret.fromSecretsManager(dbCluster.secret!, 'host'),
          SPRING_DATASOURCE_PORT: ecs.Secret.fromSecretsManager(dbCluster.secret!, 'port'),
          SPRING_DATASOURCE_DB: ecs.Secret.fromSecretsManager(dbCluster.secret!, 'dbname'),
        },
        logDriver: ecs.LogDrivers.awsLogs({
          streamPrefix: 'payment-service',
          logRetention: logs.RetentionDays.ONE_WEEK,
        }),
      },
      circuitBreaker: { rollback: true },
      securityGroups: [appSg],
      assignPublicIp: true, // no NAT; tasks reach AWS APIs via public IPs
    });

    eventsQueue.grantSendMessages(fargate.taskDefinition.taskRole);
    dbCluster.connections.allowDefaultPortFrom(appSg);

    // ---------- Alarm topics ----------
    const pagingTopic = new sns.Topic(this, 'PagingTopic', {
      topicName: 'payment-paging-alarms',
      enforceSSL: true,
    });
    const notifyTopic = new sns.Topic(this, 'NotifyTopic', {
      topicName: 'payment-notify-alarms',
      enforceSSL: true,
    });

    // ---------- Observability ----------
    const dashboard = buildDashboard(this, {
      service: fargate.service,
      cluster: dbCluster,
      eventsQueue,
      dlq,
      loadBalancer: fargate.loadBalancer,
    });

    buildAlarms(this, {
      cluster: dbCluster,
      eventsQueue,
      dlq,
      service: fargate.service,
      pagingTopic,
      notifyTopic,
    });

    // ---------- Outputs ----------
    new cdk.CfnOutput(this, 'AlbDns', { value: fargate.loadBalancer.loadBalancerDnsName });
    new cdk.CfnOutput(this, 'QueueUrl', { value: eventsQueue.queueUrl });
    new cdk.CfnOutput(this, 'DashboardName', { value: dashboard.dashboardName });
  }
}
