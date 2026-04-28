# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

**This repo is a scaffold.** Directory structure, CDK stack signatures, key interfaces, Flyway migrations, and CI workflows are in place; most method bodies are stubs marked with `TODO`. The README is the design doc to build against, and `docs/BUILD_ORDER.md` defines the implementation sequence (Phase 1 = pure domain, Phase 9 = polish). Treat the README's design claims as load-bearing — implementations are expected to match them rather than diverge.

When adding code, check whether the surrounding file is a stub. New work should fit into the existing hexagonal package layout, not create parallel structures.

## Repository layout

Two top-level builds:

- `app/` — Spring Boot 3 / Java 21 service, built with Gradle (`settings.gradle` includes only `app`).
- `infrastructure/` — AWS CDK in TypeScript, separate npm project. Two stacks: `NetworkStack` (stable: VPC only) and `PaymentServiceStack` (everything else: SGs, Aurora, Fargate, SQS, observability).

The split between the two stacks is intentional — keep network resources stable and put per-deploy resources in `PaymentServiceStack`. Security groups live in `PaymentServiceStack` rather than `NetworkStack` so SG-to-SG rules (ALB → app, app → DB) stay intra-stack; otherwise the auto-wired ALB→app ingress would form a dependency cycle with the network stack.

## Common commands

```bash
# App
./gradlew bootRun                  # run service locally (Testcontainers Postgres via local profile)
./gradlew test                     # all unit + integration tests
./gradlew test --tests '<FQCN>'    # single test class
./gradlew test --tests '<FQCN>.<method>'   # single test method
./gradlew build jacocoTestReport   # full PR-equivalent build with coverage
./gradlew spotlessCheck            # lint (Google Java Format AOSP, 1.22.0)
./gradlew spotlessApply            # auto-format

# Infrastructure (run from infrastructure/)
npm ci
npx tsc --noEmit                   # type check
npx cdk synth --all                # synth both stacks (no AWS account needed)
npx jest                           # CDK unit tests (structural invariants)
npx cdk deploy --all
npx cdk deploy -c running=true     # demo mode (Fargate task count > 0)
npx cdk deploy -c running=false    # idle mode (scale to zero)
```

The test suite registers both JUnit Jupiter and **jqwik** engines (see `app/build.gradle`); property-based tests in the domain layer rely on jqwik being included.

### One-time setup gotcha

`gradle/wrapper/gradle-wrapper.jar` is intentionally not committed (per `docs/SETUP.md`). On a fresh clone, generate it once with a system Gradle: `gradle wrapper --gradle-version 8.10`. After that, `./gradlew` works.

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

CDK unit tests (`infrastructure/test/`) treat security/reliability properties as code-reviewable assertions: Aurora `storageEncrypted: true`, DB SG ingress only from app SG, every Lambda has a log retention policy, every queue has a redrive policy, every SNS topic enforces TLS. New constructs should preserve these invariants — failing tests indicate a real regression, not a test to update.

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
- `app/src/main/resources/db/migration/` — V1–V4 Flyway migrations
