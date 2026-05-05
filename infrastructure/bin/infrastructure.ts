#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { GithubOidcStack } from '../lib/github-oidc-stack';
import { NetworkStack } from '../lib/network-stack';
import { PaymentServiceStack } from '../lib/payment-service-stack';

const app = new cdk.App();

const env = {
  account: process.env.CDK_DEFAULT_ACCOUNT,
  region: process.env.CDK_DEFAULT_REGION ?? 'eu-central-1',
};

// Bootstrap stack — provisions the GitHub Actions OIDC federation and the deploy/readonly
// roles. Deployed once per account; populate `githubOrg` via cdk.json context, -c githubOrg=...,
// or the GITHUB_ORG env var. The role ARNs are stack outputs to be copied into repo secrets.
const githubOrg = app.node.tryGetContext('githubOrg') ?? process.env.GITHUB_ORG;
if (!githubOrg) {
  throw new Error(
    'githubOrg is required. Set it in cdk.json under "context", pass -c githubOrg=<org>, or export GITHUB_ORG. ' +
      'It is baked into the OIDC trust policy as repo:<org>/<repo>:... — a wrong value silently produces a role no GitHub Actions run can assume.',
  );
}
const githubRepo =
  app.node.tryGetContext('githubRepo') ?? process.env.GITHUB_REPO ?? 'payment-intent-service';

new GithubOidcStack(app, 'PaymentBootstrap', { env, githubOrg, githubRepo });

const network = new NetworkStack(app, 'PaymentNetwork', { env });

new PaymentServiceStack(app, 'PaymentService', {
  env,
  vpc: network.vpc,
});
