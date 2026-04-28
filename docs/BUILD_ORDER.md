# Build Order

A suggested sequence for implementing the project. Each step is meaningful on its own and can be committed independently. The order is chosen so that you always have a working subset.

## Phase 1 — Domain core (no infrastructure)

Goal: a tested domain model with no Spring, no database, no AWS dependencies.

1. `Money` value object with currency-safe arithmetic. Property-based tests with jqwik.
2. `PaymentIntentId`, `IdempotencyKey`, `MerchantId`, `PaymentMethod` value objects.
3. `PaymentState` enum and the transition table (a `Map<PaymentState, Set<PaymentState>>` or sealed interface).
4. `PaymentIntent` aggregate with command methods (`create`, `attachPaymentMethod`, `confirm`, `markAuthorized`, `markFailed`, `markRequiresAction`, `capture`, `cancel`).
5. Domain events as sealed interface or records.
6. Child entities: `PaymentAttempt`, `Capture`, `Refund`.
7. `refund` command on the aggregate (creates a `Refund` child).

At the end of phase 1: full unit test coverage of state transitions and invariants. No Spring on the classpath of the domain package yet.

## Phase 2 — Application layer

Goal: use cases that orchestrate the aggregate, behind ports.

1. Repository ports: `PaymentIntentRepository`, `OutboxRepository`, `IdempotencyRepository`.
2. `PaymentProcessor` port with `AuthorizationResult` sealed interface (Authorized, Declined, Unknown).
3. Use cases: `CreatePaymentIntent`, `ConfirmPaymentIntent`, `CapturePayment`, `CancelPaymentIntent`, `CreateRefund`.
4. `IdempotencyService` with the five-case handling.
5. ArchUnit tests asserting domain has no infrastructure dependencies.

## Phase 3 — Persistence adapters

Goal: real Postgres-backed persistence, integration-tested with Testcontainers.

1. Flyway migrations: `V1__payment_intents.sql`, `V2__outbox.sql`, `V3__idempotency.sql`, `V4__processed_events.sql`.
2. JDBC repositories implementing the ports.
3. Optimistic locking via `version` column on `payment_intents`.
4. `OutboxDispatcher` with `@Scheduled`, `FOR UPDATE SKIP LOCKED`, exponential backoff.
5. Testcontainers integration tests.

## Phase 4 — REST adapter

Goal: HTTP API with idempotency middleware.

1. DTOs and controllers for the documented endpoints.
2. Idempotency filter or interceptor.
3. Exception handlers mapping domain exceptions to HTTP status codes.
4. OpenAPI spec generation (springdoc-openapi).
5. Spring Boot integration tests with `@SpringBootTest`.

## Phase 5 — Async pipeline

Goal: SQS publishing and consumption working end-to-end with LocalStack locally.

1. SQS publisher (the dispatcher's destination).
2. Lambda consumer code (or `@SqsListener` for local testing).
3. Processed events table and consumer-side dedup.
4. Stub `PaymentProcessor` with configurable failure rates.
5. Reconciliation event handler for `Unknown` outcomes.

## Phase 6 — Infrastructure

Goal: CDK stacks deploying everything to AWS.

1. `NetworkStack`: VPC, subnets, security groups.
2. `PaymentServiceStack` skeleton with Aurora Serverless v2.
3. ECR repository, Fargate service, ALB.
4. SQS queues, DLQ, Lambda function.
5. CDK unit tests for invariants.
6. First successful `cdk deploy`.

## Phase 7 — Observability

Goal: dashboards, alarms, and metrics tied to real signals.

1. Micrometer + CloudWatch registry configuration.
2. Custom metrics from domain event handlers.
3. CloudWatch dashboard definition in `infrastructure/lib/observability/dashboard.ts`.
4. Alarms in `infrastructure/lib/observability/alarms.ts`.
5. Composite alarm for system-down.
6. Logs Insights queries for the dashboard.

## Phase 8 — CI/CD

Goal: automated PR checks and deploys.

1. `.github/workflows/pr-checks.yml` with parallel jobs.
2. CDK OIDC role in the network stack.
3. `.github/workflows/deploy.yml` with the full sequence including rollback.
4. Branch protection rules configured in GitHub.
5. PR template and CODEOWNERS.

## Phase 9 — Polish

1. Architecture diagram as a real image (Mermaid in README, or excalidraw committed).
2. `cdk diff` PR comment automation.
3. `nightly.yml` workflow.
4. Final README pass — ensure every claim matches the code.

## Time budget

For someone with the relevant background, working focused but not full-time: roughly 2–3 weeks. The domain phase is the most fun and the temptation is to over-engineer it; resist. Phases 6–8 are where the showcase value is, and they need time.
