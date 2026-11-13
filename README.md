# Multi-Resource Lab Booking System (MRLBS) Backend

## Purpose

This repository contains the initial backend scaffold for the CS455 Software Engineering project. The backend will provide a REST API for the MRLBS; the React and TypeScript frontend is maintained separately.

## Technology stack

- Java 21
- Spring Boot 4.1.1
- Maven
- Spring Web MVC, Spring Security OAuth2 Client/OIDC, Spring Data JPA, Bean Validation, and Spring Boot Actuator
- PostgreSQL
- Spring Boot Test (with H2 for isolated tests)

## Architecture overview

The application uses a standard Maven source layout and a layered package structure under `com.mrlbs`. Web controllers expose REST endpoints, configuration is centralized, and persistence uses Spring Data JPA with Flyway migrations. The frontend is a separate React and TypeScript repository. Future AI processing is expected to use asynchronous workflows.

Members and scoped administrators sign in with an IITK email and password. Members use **Forgot password or first-time sign-in?** to request a six-digit email code and set a password of 12 to 128 characters. Codes expire after five minutes and allow five incorrect attempts. Setting a password revokes existing sessions for that account. Password hashes and session revocation IDs are stored on the account record. Sessions expire after 30 minutes of inactivity and use an HTTP-only cookie.

The API exposes `GET /api/health`, `GET /api/auth/me`, and `GET /api/auth/csrf`. Send the CSRF header and session cookie with `POST /api/auth/login`, `POST /api/auth/forgot-password`, `POST /api/auth/reset-password`, `POST /api/auth/logout`, and other write requests. Public GET endpoints include health and the resource catalog. Member data and admin actions require an authenticated session and the applicable role.

`POST /api/resources` additionally requires the authenticated Spring Security principal to have the `ROLE_ADMIN` authority. User profile creation and role assignment are tracked separately under FR-1.2 / BOOK-11.

## Prerequisites

- JDK 21
- Maven 3.9.11 (available through the included Maven Wrapper)
- PostgreSQL 14 or newer for local development

## Local setup

1. The default development profile stores data in the local H2 database at `./data/mrlbs`. Configure SMTP for email verification.
2. From this directory, run `./mvnw spring-boot:run` (macOS/Linux) or `mvnw.cmd spring-boot:run` (Windows).
3. Start the frontend and open `http://localhost:5173`.
4. To use PostgreSQL, configure the connection and use the `postgres` profile.

The PostgreSQL profile updates the schema on startup. `V13__add_member_password_credentials.sql` also provides the additive schema change for managed migrations. Existing member accounts can set their first password through the emailed-code flow.

## Admin account provisioning

The backend provisions the three scoped admin accounts on startup when their initial password settings are present.

| Admin username | Initial password setting |
| --- | --- |
| `lhc@iitk.ac.in` | `MRLBS_LHC_INITIAL_PASSWORD` |
| `gpus@iitk.ac.in` | `MRLBS_GPUS_INITIAL_PASSWORD` |
| `kdlab@iitk.ac.in` | `MRLBS_KDLAB_INITIAL_PASSWORD` |

Set each password through the backend environment or deployment secret manager. Use a distinct password of 12 to 128 characters per account. Provisioning stores a hash and skips accounts that already have a password. The public registration and OTP flow applies to member accounts. Fictional admin usernames are reserved and do not receive OTP email.

## PostgreSQL configuration

The local defaults expect PostgreSQL on `localhost:5432`, database `mrlbs`, and username `mrlbs`. Set a password through the environment rather than committing credentials. Create the database/user in PostgreSQL before starting the application.

## Environment variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/mrlbs` | JDBC connection URL |
| `DB_USERNAME` | `mrlbs` | Database username |
| `DB_PASSWORD` | empty | Database password |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Comma-separated allowed frontend origins for `/api/**` |
| `SPRING_RABBITMQ_ADDRESSES` | `amqps://...` | AMQP/AMQPS broker URI for asynchronous review queueing |
| `GEMINI_API_KEY` | empty | Google Gemini API key for structured AI review agent evaluation |
| `GEMINI_MODEL` | `gemini-2.0-flash` | Gemini model name for review evaluation |
| `TELEMETRY_SERVICE_BASE_URL` | `http://localhost:8085` | Base URL of the standalone hardware Resource Telemetry Service |

Set `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, and `MAIL_FROM` for OTP delivery. On HTTPS deployments set `SESSION_COOKIE_SECURE=true`. Use a same-site frontend and API where possible. For a cross-site frontend, use `SESSION_COOKIE_SAME_SITE=none`, HTTPS, and an explicit `CORS_ALLOWED_ORIGINS` entry. Browser third-party cookie settings can still block a cross-site session.

## Resource onboarding

Send an authenticated Admin request to `POST /api/resources` with JSON such as:

```json
{
  "name": "Confocal Microscope 1",
  "type": "Microscope",
  "location": "Biology Building, Room 204",
  "capacity": 1,
  "rules": ["Complete safety training before booking", "Bookings are limited to 2 hours"]
}
```

Set `capacity` to `null` when the resource has no capacity limit. A successful request returns `201 Created` and the persisted resource, including its generated ID.

## How to run

### Option A: Running with PostgreSQL (Cloud / Neon / Local DB)

A convenience script [`runpostgres.ps1`](runpostgres.ps1) is provided for running against PostgreSQL:

```powershell
.\runpostgres.ps1
```

**What this script does:**
1. Checks for and frees port `8080` if another backend process is currently holding it.
2. Automatically loads environment variables from `postgres.local.env` (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, SMTP settings).
3. Compiles the executable JAR if not already built.
4. Starts the application with `--spring.profiles.active=postgres`.

> **Note:** The `postgres.local.env` file is git-ignored for security. Place your database connection string and credentials in `postgres.local.env` in this directory or the workspace root.

### Option B: Running with local H2

To run the backend with the local H2 database:

- **Windows:**
  ```powershell
  .\mvnw.cmd spring-boot:run
  ```
- **macOS / Linux:**
  ```bash
  ./mvnw spring-boot:run
  ```

### Building the Executable JAR

```powershell
.\mvnw.cmd clean package -DskipTests
```

## How to run tests

All unit and integration tests use an embedded H2 test database and mock identities:

```powershell
.\mvnw.cmd test
```

## Initialized Sample Test Dataset

When starting under the `postgres` or `dev` profiles, the application pre-seeds test resources:
- **Lecture Halls:** `L1` (cap 100), `L2` (cap 50), `L3` (cap 50), `L4` (cap 50), `L5` (cap 100) — base credit rate `0`, minimum duration `1 hr`, open booking policy.
- **GPU Resources:** `GPU-A100-01` (base rate `20` credits/hr) and `GPU-H100-01` (base rate `35` credits/hr) — admin approval required.
- **Test Accounts:** `member@iitk.ac.in`, `student@iitk.ac.in`, and `admin@iitk.ac.in` seeded with `1,000` credits each for testing dynamic credit pricing and booking submissions.

## AI Request Review Agent (Asynchronous Advisory Pipeline)

The backend incorporates an asynchronous, event-driven AI Request Review Agent to provide purely advisory recommendations for pending lab booking requests on `APPROVAL_REQUIRED` resources (such as high-demand DGX GPUs).

```
[ Booking Submission ] 
         │ (Transactional Outbox)
         ▼
[ booking_outbox_events ] ──(afterCommit)──> [ RabbitMQ Exchange ]
                                                    │
                                                    ▼
                                           [ Review Queue ]
                                                    │
                                                    ▼
                                    [ BookingAiReviewConsumer ]
                                      ├── Query Requester Track Record
                                      ├── Query External Hardware Telemetry
                                      ├── Evaluate via Gemini LLM
                                      ▼
                                    [ booking_ai_reviews ]
                                                    │ (Advisory Card)
                                                    ▼
                                    [ Admin Review Dashboard ]
                                        ├── Approve (Human Decision)
                                        └── Reject  (Human Decision)
```

### Key Architectural Characteristics

1. **Transactional Outbox & Reliable Queueing:**
   - On booking submission, a pending outbox event is staged in `booking_outbox_events` and committed in the same database transaction as the booking.
   - Upon transaction commit (`TransactionSynchronizationManager.afterCommit`), the event is dispatched to RabbitMQ with publisher confirms.
   - Consumers acknowledge messages only after evaluation and persistence are complete. Dead-letter exchanges (DLX) handle poison messages without blocking queue throughput.

2. **Integration with Simulated Hardware Telemetry (Demonstration / Mock Service):**
   - Resources map to physical hardware identifiers via `resource_telemetry_mappings` (Flyway migration `V8`).
   - Because live institutional access to real IIT Kanpur cluster hardware telemetry APIs is restricted/unavailable, a dedicated standalone **Resource Telemetry Mock Service** was built specifically for feature implementation and realistic demonstration (deployed on Render at `https://resource-telemetry-service.onrender.com` or local fallback `http://localhost:8085`).
   - The backend queries this mock service to simulate real-world cluster telemetry:
     - **Present Operating Health Snapshot:** point-in-time operating status, GPU/memory utilization %, temperature, power draw.
     - **Exact-Interval Historical Telemetry:** hardware metrics captured strictly during eligible completed past reservations.
     - **Live Monitoring Dashboard:** A visual dark-mode metrics and telemetry dashboard is available at `https://resource-telemetry-service.onrender.com/`.

3. **Strict Non-Punitive & Attribution Boundaries:**
   - **Device-Level Attribution:** Telemetry measures hardware device metrics during exclusive windows; individual job or user-level attribution is not captured.
   - **Isolated Present Health:** Present hardware state (e.g. high load or elevated temperature from an ongoing job) is strictly separated from the requester's history and never attributed to the requester without explicit evidence.
   - **Booking Status Accounting:** All bookings (completed, cancelled, rejected, pending) are accurately distinguished. Standard cancellations or rejections are treated as normal scheduling adjustments, never as misconduct.
   - **Missing Telemetry Safety:** If historical telemetry is absent or from demonstration fixtures, the agent defaults to `NEEDS_HUMAN_REVIEW` and never rejects solely on missing telemetry.
   - **Advisory Confidence Score:** The `confidenceScore` (0.1 to 1.0) reflects the agent's confidence in its recommendation based on evidence completeness, not a user risk score.

4. **Human Decision Enforcement:**
   - The AI Review Agent is **strictly advisory**. The consumer only writes to `booking_ai_reviews`.
   - The booking remains in `PENDING_APPROVAL` until an authenticated administrator explicitly reviews the advisory card and invokes `POST /api/v1/bookings/{id}/approve` or `POST /api/v1/bookings/{id}/reject`.

5. **Graceful Degradation:**
   - If RabbitMQ or Gemini is temporarily unreachable, requests automatically transition to `FALLBACK_HUMAN_ONLY` or deterministic rule-based evaluation, allowing human administrators to proceed with approvals immediately without interruption.

## Current status

- **Authentication:** Institutional Google OIDC and verified hosted-domain checks (`@iitk.ac.in`) with demo administrator mode for local evaluation.
- **Resource Management:** Admin onboarding, booking rules, capacity management, and hardware telemetry mappings.
- **Booking Engine:** Slot conflict detection, dynamic credit pricing, reservation windows, waitlist queues, and credit balances.
- **AI Request Review Agent:** Fully functional asynchronous RabbitMQ outbox pipeline, telemetry client, Gemini LLM evaluation, and advisory Admin dashboard cards.

## AI Engineering Log

Meaningful AI-assisted engineering work is recorded in [AI_ENGINEERING_LOG.md](AI_ENGINEERING_LOG.md). [AGENTS.md](AGENTS.md) contains the workflow that future AI agents must follow.

##To run a tesfile
.\mvnw.cmd test -Dtest=FcfsBookingAndWaitlistTests '-Dspring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE'

##to run all testfiles
.\mvnw.cmd test '-Dtest=!LivePhase3VerificationTest,!LiveRabbitMqConnectionTest' '-Dspring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE'
