#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { NetworkStack } from '../lib/network-stack';
import { PaymentServiceStack } from '../lib/payment-service-stack';

const app = new cdk.App();

const env = {
  account: process.env.CDK_DEFAULT_ACCOUNT,
  region: process.env.CDK_DEFAULT_REGION ?? 'eu-central-1',
};

const network = new NetworkStack(app, 'PaymentNetwork', { env });

new PaymentServiceStack(app, 'PaymentService', {
  env,
  vpc: network.vpc,
});
