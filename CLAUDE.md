# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

**Phases 1–9 complete.** Domain, application use cases, JDBC adapters, REST adapter with idempotency filter, async pipeline (outbox dispatcher + SQS consumer + reconciliation), CDK stacks, observability, CI/CD, and polish are all in. The README is the design doc — treat its claims as load-bearing and keep implementations matching it rather than diverging. `docs/BUILD_ORDER.md` records the original sequence.

When adding code, fit into the existing hexagonal package layout rather than creating parallel structures.

## Repository layout

Two top-level builds:

- `app/` — Spring Boot 3 / Java 21 service, built with Gradle (`settings.gradle` includes only `app`).
- `infrastructure/` — AWS CDK in TypeScript, separate npm project. Three stacks: `PaymentBootstrap` (one-time: GitHub Actions OIDC provider + deploy/readonly IAM roles), `NetworkStack` (stable: VPC only), `PaymentServiceStack` (per-deploy: SGs, Aurora, Fargate, SQS, observability).

The split is intentional — keep network resources stable and put per-deploy resources in `PaymentServiceStack`. Security groups live in `PaymentServiceStack` rather than `NetworkStack` so SG-to-SG rules (ALB → app, app → DB) stay intra-stack; otherwise the auto-wired ALB→app ingress would form a dependency cycle with the network stack. `PaymentBootstrap` is deployed once per AWS account; copy its `DeployRoleArn` and `ReadonlyRoleArn` outputs into the repo's `DEPLOY_ROLE_ARN` and `READONLY_ROLE_ARN` GitHub secrets.

## Common commands

```bash
# Local dependencies (run once per session)
docker compose up -d               # Postgres + LocalStack (SQS)

# App
./gradlew bootRun                  # run against compose Postgres on localhost:5432
./gradlew test                     # full suite; ITs gated on Postgres + LocalStack reachability
./gradlew test --tests '<FQCN>'    # single test class
./gradlew test --tests '<FQCN>.<method>'   # single test method
./gradlew build jacocoTestReport   # full PR-equivalent build with coverage
./gradlew spotlessCheck            # lint (Google Java Format AOSP, 1.22.0)
./gradlew spotlessApply            # auto-format

# Infrastructure (run from infrastructure/)
npm ci
npx tsc --noEmit                   # type check
npx cdk synth --all                # synth all three stacks (no AWS account needed)
npx jest                           # CDK unit tests (structural invariants)
npx cdk deploy PaymentBootstrap    # one-time per account; sets up GitHub OIDC + roles
npx cdk deploy PaymentNetwork PaymentService   # day-to-day deploys
npx cdk deploy -c running=true PaymentService  # demo mode (Fargate task count > 0)
npx cdk deploy -c running=false PaymentService # idle mode (scale to zero)
```

The integration tests (`JdbcRepositoriesIT`, `PaymentIntentControllerIT`, `AsyncPipelineIT`) are gated with `@EnabledIf` on a TCP probe of localhost:5432 (Postgres) and localhost:4566 (LocalStack). They skip cleanly when those aren't running rather than failing.

The test suite registers both JUnit Jupiter and **jqwik** engines (see `app/build.gradle`); property-based tests in the domain layer rely on jqwik being included.

## Architecture

Hexagonal layering, **enforced by ArchUnit** in `app/src/test/java/com/example/payments/architecture/HexagonalArchitectureTest.java`:

- `domain/` — aggregate, entities, value objects, events, ports. Must not depend on Spring, `java.sql`, `software.amazon..`, or any `..adapter..` package. The domain protects its own invariants; don't push validation into services.
- `application/` — use cases (one per command) and `IdempotencyService`. Talks to ports, never adapters.
- `adapter/in/{rest,sqs}` and `adapter/out/{persistence,messaging,processor}` — all framework-touching code.
- `config/` — Spring wiring.

If you violate these dependency rules ArchUnit will fail the build. Don't relax the rules — fix the dependency direction.

### Three design pillars (see README for full rationale)

1. **State machine on the aggregate.** `PaymentState` and its transition table live in `domain/model`. Invalid transitions throw `InvalidStateTransitionException` from inside `PaymentIntent`. Refunds are child entities of a `Capture`, **not** intent state transitions — `CAPTURED` is non-terminal.

2. **Three-layer idempotency.** API (`idempotency_records` table, `INSERT … ON CONFLICT DO NOTHING`, five distinct outcomes including body-mismatch and in-progress retry); outbox relay (outbox row UUID → SQS `MessageDeduplicationId`); consumer (`processed_events(event_id PK)`). A single UUID flows: outbox row id → SQS dedup id → processor idempotency key → consumer dedup key. Don't break that chain.

3. **Transactional outbox.** Outbox row is inserted in the **same transaction** as the aggregate change. `OutboxDispatcher` polls with `SELECT … FOR UPDATE SKIP LOCKED` (so it horizontally scales without coordination), exponential backoff with jitter, poison after 10 attempts. SQS FIFO uses `aggregate_id` as `MessageGroupId` for per-intent ordering with cross-intent parallelism.

### Processor integration is three outcomes, not two

`AuthorizationResult` is a sealed interface: `Authorized` / `Declined` / `Unknown`. `Unknown` (typically timeout) **must not be retried as if it were a transient error** — it triggers reconciliation via `processor.lookup()` with a hard cap of 10 attempts over 24h. Retry policy: never retry `Unknown`, never retry declines, only retry definite transient errors. The stub processor (`StubPaymentProcessor`) is calibrated to exercise every failure mode the production code is designed to handle; failure rates are tunable via `processor.stub.*` in `application.yml` for chaos testing.

### Card data

The service is out of PCI scope by design — `PaymentMethod` is a token reference, never raw card data. Logging is whitelist-based; don't log request/response fields that aren't explicitly marked safe.

## CDK invariants

CDK unit tests (`infrastructure/test/`) treat security/reliability properties as code-reviewable assertions. Current invariants: Aurora `storageEncrypted: true`, every queue has a redrive policy, every SNS topic enforces TLS, ECS deployment circuit breaker enabled, the app log group name `/aws/ecs/payment-service` matches the dashboard's Logs Insights query, and `PaymentBootstrap`'s OIDC roles are trust-scoped (deploy = main only, readonly = any branch in this repo). New constructs should preserve these invariants — failing tests indicate a real regression, not a test to update.

Jest is configured in `infrastructure/jest.config.js` with `ts-jest` and a `testPathIgnorePatterns` for `cdk.out/` — without that exclude, jest discovers test files inside Docker asset stagings and tries to run them.

`PaymentServiceStack` runs Aurora Serverless v2 with `serverlessV2MinCapacity: 0` (scale-to-zero) and `removalPolicy: DESTROY` for clean teardown — production would enable deletion protection. Aurora Serverless v2 scale-to-zero requires a recent `aws-cdk-lib` (added after the November 2024 AWS announcement); pre-2.171-ish versions reject `min: 0` with a "must be >= 0.5" validation error. There is deliberately **no NAT Gateway** for cost reasons; Fargate runs with a public IP for outbound. If adding networking, see the README "What's deliberately not implemented" section before changing this.

The `DockerImageAsset` for the Fargate image uses the **repo root** as build context (so the Dockerfile can see both `gradlew` and `app/`). A root `.dockerignore` is therefore load-bearing: without it, `infrastructure/cdk.out/` gets staged into itself recursively until paths blow up with `ENAMETOOLONG`. If you change the build context or add new directories at the repo root, update `.dockerignore` accordingly.

## CI/CD

Three workflows in `.github/workflows/`: `pr-checks.yml` (parallel: app build+test, Spotless, CDK type-check/synth/jest), `deploy.yml` (sequential on merge to main, with ECS deployment circuit breaker auto-rollback), `nightly.yml`.

AWS auth from GitHub Actions uses **OIDC federation** (no long-lived keys). Two roles are expected as repo secrets: `DEPLOY_ROLE_ARN` (trusted only from `main`) and `READONLY_ROLE_ARN` (trusted from PR branches for synth/diff). DB migrations follow **expand/contract** so deploys can roll forward and back without coordination — schema changes must be backward-compatible with the currently-deployed app version.

## Pointers worth opening first

- `app/src/main/java/com/example/payments/domain/model/PaymentIntent.java` — aggregate, state transition table, invariant checks
- `app/src/main/java/com/example/payments/application/idempotency/IdempotencyService.java` — five-case idempotency handling
- `app/src/main/java/com/example/payments/adapter/out/persistence/OutboxDispatcher.java` — `FOR UPDATE SKIP LOCKED` polling dispatcher
- `app/src/main/java/com/example/payments/adapter/out/processor/StubPaymentProcessor.java` — failure-mode simulation
- `infrastructure/lib/observability/dashboard.ts` and `alarms.ts` — operator-facing observability
- `app/src/main/java/com/example/payments/adapter/in/rest/IdempotencyFilter.java` — five-case filter wrapping POST `/v1/payment-intents/**`
- `app/src/main/java/com/example/payments/adapter/in/sqs/PaymentEventListener.java` — polling consumer, dedup-in-tx pattern
- `app/src/main/java/com/example/payments/adapter/out/reconciliation/ReconciliationJob.java` — picks up intents stuck in PROCESSING via `processor.lookup`
- `app/src/main/resources/db/migration/` — V1–V5 Flyway migrations (V5 adds `seq` column to captures/refunds for stable order on reload)
