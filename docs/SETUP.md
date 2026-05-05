# Setup

This is a scaffold. After cloning, a few one-time setup steps are needed before you can deploy.

## CDK bootstrap

Once per AWS account/region:

```bash
cd infrastructure
npm install
npx cdk bootstrap aws://ACCOUNT_ID/eu-central-1
```

## GitHub Actions secrets

The deploy workflow expects these repository secrets, set under Settings → Secrets and variables → Actions:

| Secret | Purpose |
|---|---|
| `DEPLOY_ROLE_ARN` | IAM role assumed via OIDC for deploys |
| `READONLY_ROLE_ARN` | IAM role for nightly drift detection |

Both roles' trust policies must include the GitHub OIDC provider with a `sub` claim restricted to your specific repo and branch. See README §"CI/CD" for the trust policy shape.

The IAM roles themselves are best provisioned outside this stack (chicken-and-egg with the rest of the deploy). A tiny separate `bootstrap` CDK app, or a one-time CLI command, is the right pattern.

## Branch protection

In the GitHub repo settings, enable for `main`:

- Require pull request reviews before merging (1 approval)
- Require status checks: all `pr-checks.yml` jobs
- Dismiss stale approvals on new commits
- Require linear history
- Require signed commits
- Restrict who can push to matching branches (none — only via PR)

## First deploy

```bash
cd infrastructure
npx cdk deploy --all
```

Get the ALB DNS from the CloudFormation outputs, then:

```bash
curl http://<alb-dns>/actuator/health
```

## Cost discipline

To scale the service to zero when not demoing:

```bash
npx cdk deploy -c running=false
```

To bring it back:

```bash
npx cdk deploy -c running=true
```

Aurora scales to zero automatically after 5 minutes of inactivity (configured via `serverlessV2MinCapacity: 0`).
