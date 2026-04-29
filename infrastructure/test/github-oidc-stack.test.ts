import * as cdk from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { GithubOidcStack } from '../lib/github-oidc-stack';

describe('GithubOidcStack invariants', () => {
  let template: Template;

  beforeAll(() => {
    const app = new cdk.App();
    const stack = new GithubOidcStack(app, 'TestBootstrap', {
      githubOrg: 'example-org',
      githubRepo: 'example-repo',
    });
    template = Template.fromStack(stack);
  });

  test('Provisions exactly one OIDC provider for GitHub Actions', () => {
    // CDK's iam.OpenIdConnectProvider lowers to a custom resource rather than the native
    // AWS::IAM::OIDCProvider, so assert against that shape.
    template.resourceCountIs('Custom::AWSCDKOpenIdConnectProvider', 1);
    template.hasResourceProperties('Custom::AWSCDKOpenIdConnectProvider', {
      Url: 'https://token.actions.githubusercontent.com',
      ClientIDList: ['sts.amazonaws.com'],
    });
  });

  test('Deploy role trust is scoped to main branch only', () => {
    template.hasResourceProperties('AWS::IAM::Role', {
      RoleName: 'github-actions-payments-deploy',
      AssumeRolePolicyDocument: Match.objectLike({
        Statement: Match.arrayWith([
          Match.objectLike({
            Condition: Match.objectLike({
              StringEquals: Match.objectLike({
                'token.actions.githubusercontent.com:sub':
                  'repo:example-org/example-repo:ref:refs/heads/main',
              }),
            }),
          }),
        ]),
      }),
    });
  });

  test('Readonly role trust is scoped to the repo (any branch)', () => {
    template.hasResourceProperties('AWS::IAM::Role', {
      RoleName: 'github-actions-payments-readonly',
      AssumeRolePolicyDocument: Match.objectLike({
        Statement: Match.arrayWith([
          Match.objectLike({
            Condition: Match.objectLike({
              StringLike: Match.objectLike({
                'token.actions.githubusercontent.com:sub': 'repo:example-org/example-repo:*',
              }),
            }),
          }),
        ]),
      }),
    });
  });
});
