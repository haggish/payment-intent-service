import * as cdk from 'aws-cdk-lib';
import * as iam from 'aws-cdk-lib/aws-iam';
import { Construct } from 'constructs';

interface GithubOidcStackProps extends cdk.StackProps {
  githubOrg: string;
  githubRepo: string;
}

/**
 * Bootstraps GitHub Actions OIDC federation. Stable resources separated from PaymentService so
 * the deploy stack can be torn down and re-created without losing the federation trust.
 *
 * After deploying this stack, copy the role ARNs from outputs into the repo's GitHub secrets:
 *   - DEPLOY_ROLE_ARN     → output `DeployRoleArn`
 *   - READONLY_ROLE_ARN   → output `ReadonlyRoleArn`
 *
 * Trust is scoped by GitHub's OIDC `sub` claim:
 *   - DEPLOY:   only `refs/heads/main` (push to main → real deploy)
 *   - READONLY: any branch in this repo (PR checks → synth/diff)
 *
 * Permissions use AWS managed policies for scaffold simplicity. A real production setup would
 * scope to the specific actions the deploy needs (CloudFormation, ECS, RDS, ECR, IAM PassRole,
 * SecretsManager, etc.) rather than AdministratorAccess.
 */
export class GithubOidcStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: GithubOidcStackProps) {
    super(scope, id, props);

    const provider = new iam.OpenIdConnectProvider(this, 'GitHubProvider', {
      url: 'https://token.actions.githubusercontent.com',
      clientIds: ['sts.amazonaws.com'],
    });

    const repoSubject = `repo:${props.githubOrg}/${props.githubRepo}`;

    const deployRole = new iam.Role(this, 'DeployRole', {
      roleName: 'github-actions-payments-deploy',
      assumedBy: new iam.OpenIdConnectPrincipal(provider, {
        StringEquals: {
          'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
          'token.actions.githubusercontent.com:sub': `${repoSubject}:ref:refs/heads/main`,
        },
      }),
      description: 'GitHub Actions deploy role; trusted only from main branch.',
      managedPolicies: [iam.ManagedPolicy.fromAwsManagedPolicyName('AdministratorAccess')],
    });

    const readonlyRole = new iam.Role(this, 'ReadonlyRole', {
      roleName: 'github-actions-payments-readonly',
      assumedBy: new iam.OpenIdConnectPrincipal(provider, {
        StringLike: {
          'token.actions.githubusercontent.com:sub': `${repoSubject}:*`,
        },
        StringEquals: {
          'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
        },
      }),
      description: 'GitHub Actions read-only role; trusted from any branch in this repo.',
      managedPolicies: [iam.ManagedPolicy.fromAwsManagedPolicyName('ReadOnlyAccess')],
    });

    new cdk.CfnOutput(this, 'DeployRoleArn', {
      value: deployRole.roleArn,
      description: 'Copy to repo secret DEPLOY_ROLE_ARN',
    });
    new cdk.CfnOutput(this, 'ReadonlyRoleArn', {
      value: readonlyRole.roleArn,
      description: 'Copy to repo secret READONLY_ROLE_ARN',
    });
  }
}
