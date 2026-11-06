# MRLBS verification report

- **Run date** 2026-11-06
- **Baselines** backend `024af0c` and frontend `bce1818`, both from `main`
- **Environment** Windows, Temurin JDK 21, Maven Wrapper 3.9.11, Node 24.19, Spring Boot with H2, and Vitest with jsdom

The frontend test and coverage changes are in [frontend PR #28](https://github.com/CS455-Team-07/mrlbs-frontend/pull/28).

## Unit, integration, system, and adversarial tests

| Level | Evidence | Result |
| --- | --- | --- |
| Unit | JUnit and Mockito exercised pricing, booking rules, OTP consumption and mail failures, AI fallback, telemetry parsing, and availability calculations. Vitest exercised identity storage and configuration. | Passed in the local suites. |
| Integration | Spring Security and MockMvc exercised booking RBAC, waitlist ownership, resource onboarding, AI review scope, and both OTP send routes. H2 exercised repository and transaction paths. React Testing Library exercised member sign-in, draft and confirm, admin approvals, and booking cancellation with mocked HTTP. | Passed in the local suites. |
| System | `VerificationSystemTests` started embedded Tomcat on a random port. A Java HTTP client verified health and a filtered 120-resource search through the server, Spring MVC, Hibernate, and H2. The AI review lifecycle test exercised booking, review, and approval with local test doubles. | Passed in the local suites. |
| Adversarial | A lecture-hall admin requested a GPU review by UUID, a member requested the same admin route, and 16 concurrent callers replayed one verification code. Additional cases exercised waitlist ownership and stale offers. | Cross-scope access returned 404, the member received 403, and only one replay attempt succeeded per code. |

On the original `main`, 153 of 154 backend local tests passed. A booking queue test failed Mockito strict mode because its resource ID stub was unused. On the frontend, 33 of 46 passed. The 13 failures used outdated login, availability, or booking workflow expectations. The updated tests preserve assertions for the draft and confirmation requests, CSRF, rejection details, and cancellation. The frontend production build succeeded. Frontend lint still reports three errors and three warnings in untouched application files.

| Suite | Baseline pass count | Final pass count | Final failures |
| --- | ---: | ---: | ---: |
| Backend JUnit, excluding two live AMQP classes | 153 / 154 | 159 / 159 | 0 |
| Frontend Vitest | 33 / 46 | 46 / 46 | 0 |

Two live AMQP test classes were excluded. SMTP, Gemini, RabbitMQ, and Google OIDC were simulated or bypassed by local test fixtures. The system run covers the API over a real loopback socket. Frontend tests cover browser components with simulated HTTP. Live PostgreSQL, deployed services, and browser-to-backend sign-in remain outside the measured scope.

## Code coverage

Backend coverage uses JaCoCo 0.8.13 against the local JUnit suite. Frontend coverage uses Vitest 5.0.3 with the V8 provider against application TypeScript sources. The two reports measure different executables and should be read separately.

| Backend metric | Covered | Total | Coverage |
| --- | ---: | ---: | ---: |
| Lines | 2,611 | 3,757 | 69.50% |
| Instructions | 12,627 | 18,406 | 68.60% |
| Branches | 1,033 | 2,196 | 47.04% |
| Methods | 470 | 676 | 69.53% |

| Frontend metric | Covered | Total | Coverage |
| --- | ---: | ---: | ---: |
| Lines | 708 | 1,322 | 53.55% |
| Statements | 733 | 1,443 | 50.79% |
| Functions | 149 | 341 | 43.69% |
| Branches | 485 | 1,239 | 39.14% |

## Concurrency

| Contention scenario | Invariant checked | Evidence |
| --- | --- | --- |
| OTP replay | One successful use per issued code. | `OtpRecoveryTests.parallelVerificationConsumesTheSameCodeOnlyOnce` accepted 16 of 16 attempts on the baseline. It accepted exactly one per round across 12 rounds of 16 attempts after the fix. |
| Two booking requests for the same slot | Only one confirmation. | `FcfsBookingAndWaitlistTests` verifies the second request is waitlisted or rejected. |
| Competing waitlist allocations | One booking is confirmed when a slot becomes available. | `WaitlistAllocationTests` asserts exactly one confirmation under contention. |
| Concurrent cancellation | A booking's credits are refunded once. | `WaitlistOfferExpiryAndRefundTests.testConcurrentCancellationExactlyOnceRefund` checks the resulting balance and cancellation state. |

The booking and waitlist races ran against one JVM and H2. Production PostgreSQL locking and cross-instance behavior need separate evidence.

## Security and threat model

Protected assets include member identities and verification codes, booking and credit records, AI review and telemetry summaries, and deployment credentials. The main trust boundaries are the browser and API, member and scoped-admin roles, application and database, and the integrations for identity, mail, telemetry, Gemini, and RabbitMQ.

| Threat | Control and verification | Remaining exposure |
| --- | --- | --- |
| Scoped admin reads another category's booking review | The direct endpoint uses the authenticated OIDC principal and applies the same resource category boundary as the approval list. MockMvc checks matching admin, mismatched admin, and member. | Unscoped administrators have broad access by policy. |
| Verification code theft or replay | Codes were removed from process output. Atomic compare-and-remove enforces single use. A failed SMTP attempt removes only its own code. Endpoint responses omit SMTP diagnostics. | The OTP store is process-local and allows repeated invalid guesses. |
| Source exposure of database credentials | PostgreSQL URL, username, and password now require deployment environment variables. A tracked-source scan confirmed the removed credential pattern is absent from production source on the fix branch. | Previously committed values may remain in history. The database owner must rotate exposed credentials and update deployment secrets. |
| Booking or waitlist tampering | Method-level roles, resource and owner checks, and CSRF protection for booking mutations are exercised by the local suite. | Demo authentication accepts caller-supplied identity when enabled. It is a test or demonstration boundary and is unsafe for untrusted traffic. |
| Sensitive data crosses the AI integration boundary | Observability tests exercise redaction. Review tests cover malformed output and human fallback. | Provider retention, production traffic, and integration credentials were outside this run. |

## Performance and load

The exact-slot search test seeded 120 matching resources, one overlapping confirmed booking, and one overlapping cancelled booking. Each run warmed the endpoint with three requests, then measured 12 sequential loopback HTTP requests. Every response contained 120 resources and marked only the confirmed booking's resource unavailable. The benchmark below ran without coverage instrumentation.

| Measure | Baseline | Optimized |
| --- | ---: | ---: |
| Hibernate query executions per search | 121 | 2 |
| Local p50 | 56 ms | 20 ms |
| Local p95 | 102 ms | 35 ms |

The change batches confirmed booking windows for the returned resource IDs. It retains half-open overlap boundaries and the day-search behavior that reports availability when the day has an open gap. It also fetches booking rules with the resource search query. The query counts measure Hibernate query executions and exclude implicit ORM statements. The 98.3% reduction in counted queries is deterministic in the fixture. The measured 66% drop in p95 describes this local run.

The search test also sends a burst of 16 simultaneous HTTP requests for the 120-resource catalog and checks every response. All 16 completed successfully in 83 ms wall time without JaCoCo. This measures a small warm local workload. Sustained traffic, production-sized datasets, and external-service latency remain unmeasured. The separate booking service load benchmark lives in [BOOK-115](https://github.com/CS455-Team-07/mrlbs-backend/pull/32).

## Failure and recovery

| Injected or simulated failure | Expected recovery | Evidence |
| --- | --- | --- |
| First OTP delivery fails | Both send endpoints report a generic retryable error. The failed code is unusable and a second send can succeed. | `VerificationSystemTests.smtpFailureDoesNotReturnSuccessOrLeakInternalDetails` and `OtpRecoveryTests.rejectedDeliveryInvalidatesTheCodeAndAllowsAResendWithoutLoggingIt` |
| RabbitMQ fails to connect or NACKs a publish | Pending outbox data is retained. A connection failure switches the booking to a human review path. A broker ACK marks a publication sent. | `BookingEventPublisherTest` uses a mocked broker. Automatic replay after a real outage remains unverified. |
| Duplicate broker delivery | The completed review is not regenerated and the duplicate is acknowledged. | `BookingAiReviewConsumerTest` |
| Gemini fails or returns malformed output | AI review falls back to a human decision. | `AiReviewAgentServiceTest` and `AiAgentObservabilityTest` |
| Offer expires during downtime | The application-ready recovery path expires the offer and hands the slot to the next member in FIFO order. | `WaitlistOfferExpiryAndRefundTests.testRestartRecovery`. The test calls the recovery hook with a persisted stale offer. |

## Defects discovered and fixed

| Finding | Evidence on `main` | Fix and verification |
| --- | --- | --- |
| PostgreSQL profile used embedded credential defaults | Direct inspection of the tracked profile found credential fallback values. | Replaced the defaults with required environment variables and scanned production source for the removed pattern. Rotation is a separate operational task. [Backend issue #36](https://github.com/CS455-Team-07/mrlbs-backend/issues/36) |
| Direct AI review lookup crossed resource scopes | A lecture-hall admin received HTTP 200 for a GPU booking review. | Require resource scope on the direct read. Same-scope returns 200, wrong scope returns 404, and member access returns 403. [Backend issue #37](https://github.com/CS455-Team-07/mrlbs-backend/issues/37) |
| Parallel requests could reuse one OTP | The regression test observed 16 successful uses of the same code. | Consume atomically with compare-and-remove. Across 12 rounds, exactly one request succeeded per code. [Backend issue #38](https://github.com/CS455-Team-07/mrlbs-backend/issues/38) |
| SMTP failure falsely reported successful delivery and exposed OTPs in process output | With the sender throwing, the service returned normally and emitted the code. | Remove code logging, invalidate the failed entry, and propagate delivery failure. The API returns a safe error and a resend succeeds. [Backend issue #39](https://github.com/CS455-Team-07/mrlbs-backend/issues/39) |
| Catalog search made one availability query per resource | The 120-resource fixture generated 1,452 Hibernate query executions across 12 searches. | Bulk lookup reduced the same fixture to 24 query executions and preserved results. [Backend issue #40](https://github.com/CS455-Team-07/mrlbs-backend/issues/40) |

The frontend PR also repairs the automated checks for sign-in and booking. The component still presents a time-change workflow backed by a mocked `PUT /api/bookings/{id}/time-window` in its tests. The corresponding route is absent from the backend baseline and this change. Treat those tests as UI evidence only. [Frontend issue #27](https://github.com/CS455-Team-07/mrlbs-frontend/issues/27)

## Reproduction

Backend, from `mrlbs-backend` in PowerShell

```powershell
.\mvnw.cmd clean 'org.jacoco:jacoco-maven-plugin:0.8.13:prepare-agent' '-Dtest=!LivePhase3VerificationTest,!LiveRabbitMqConnectionTest' test 'org.jacoco:jacoco-maven-plugin:0.8.13:report'
.\mvnw.cmd '-Dtest=VerificationSystemTests' test
```

Open `target/site/jacoco/index.html` for coverage, `target/site/jacoco/jacoco.xml` for counts, and `target/surefire-reports` for test details. The second command runs the HTTP benchmark without JaCoCo and prints `CATALOG_BENCHMARK` and `CATALOG_BURST`.

Frontend, from `mrlbs-frontend`

```powershell
npm ci
npm run test:coverage
npm run build
```

Open `coverage/index.html` for the HTML report and `coverage/coverage-summary.json` for the raw counts.
