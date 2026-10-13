# Multi-Resource Lab Booking System (MRLBS) Backend

## Purpose

This repository contains the initial backend scaffold for the CS455 Software Engineering project. The backend will provide a REST API for the MRLBS; the React and TypeScript frontend is maintained separately.

## Technology stack

- Java 21
- Spring Boot 4.1.1
- Maven
- Spring Web MVC, Spring Security, Spring Boot Mail, Spring Data JPA, Bean Validation, and Spring Boot Actuator
- PostgreSQL
- Spring Boot Test (with H2 for isolated tests)

## Architecture overview

The application uses a standard Maven source layout and a layered package structure under `com.mrlbs`. Web controllers expose REST endpoints, configuration is centralized, and persistence uses Spring Data JPA with Flyway migrations. The frontend is a separate React and TypeScript repository. Future AI processing is expected to use asynchronous workflows.

Institutional email one-time-code authentication is enabled by default. The backend sends a short-lived code only to an address ending in `@iitk.ac.in`, binds it to the browser session and exact address, limits attempts, and establishes a server session after successful verification. This checks access to that mailbox; it is a workaround for tenants where users cannot grant OAuth app consent, and it does not prove other institutional identity attributes. Local demo mode is available only when `AUTH_ENABLED=false` is set explicitly.

The API exposes `GET /api/health`, `GET /api/auth/me`, `GET /api/auth/csrf`, `POST /api/auth/request-code`, `POST /api/auth/verify-code`, and `POST /api/resources`. Business API endpoints require authentication except health, CSRF-token retrieval, and the two code endpoints. Actuator health is also exposed at `/actuator/health`. Resource onboarding records a name, type, location, optional positive capacity, and at least one booking rule. The resource and its ordered rules are stored in PostgreSQL through JPA and Flyway.

`POST /api/resources` additionally requires the authenticated Spring Security principal to have the `ROLE_ADMIN` authority. User profile creation and role assignment are tracked separately under FR-1.2 / BOOK-11.

## Prerequisites

- JDK 21
- Maven 3.9.11 (available through the included Maven Wrapper)
- PostgreSQL 14 or newer for local development

## Local setup

1. Configure an SMTP account or relay that is permitted to send verification email. Put its local settings in `postgres.local.env` in the workspace root; do not commit it.
2. From this directory, run `./mvnw spring-boot:run` (macOS/Linux) or `mvnw.cmd spring-boot:run` (Windows).
3. Start the frontend and open `http://localhost:5173`.
4. Open `http://localhost:5173`, enter an `@iitk.ac.in` address, retrieve the email code, and enter it to sign in.

The development profile is active by default. It uses H2 with Hibernate schema updates for local data; the in-memory database resets when the backend stops. PostgreSQL deployments use Flyway migrations from `src/main/resources/db/migration`.

Email-domain checking is performed by the backend. A code is not returned by the API; delivery must be configured before authentication can complete.

## PostgreSQL configuration

The local defaults expect PostgreSQL on `localhost:5432`, database `mrlbs`, and username `mrlbs`. Set a password through the environment rather than committing credentials. Create the database/user in PostgreSQL before starting the application.

## Environment variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/mrlbs` | JDBC connection URL |
| `DB_USERNAME` | `mrlbs` | Database username |
| `DB_PASSWORD` | empty | Database password |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Comma-separated allowed frontend origins for `/api/**` |
| `AUTH_ENABLED` | `true` | Enable institutional email verification; set `false` only for the local demo identity |
| `INSTITUTIONAL_DOMAIN` | `iitk.ac.in` | Required institutional email domain |
| `SPRING_MAIL_HOST` | none | SMTP server host |
| `SPRING_MAIL_PORT` | Spring Mail default | SMTP server port |
| `SPRING_MAIL_USERNAME` | none | SMTP username, if required |
| `SPRING_MAIL_PASSWORD` | none | SMTP password or app-specific credential; keep it out of source control |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` | `true` for authenticated SMTP | Whether SMTP authentication is enabled |
| `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` | provider-specific | Enable STARTTLS when required by the SMTP server |
| `MAIL_FROM` | none | Sender address accepted by the configured SMTP server |

For production, configure SMTP settings through the deployment secret manager and use HTTPS with secure session cookies. Codes expire after ten minutes and are limited to five verification attempts. Requests are rate-limited per address in each backend process.

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
2. Automatically loads environment variables from `postgres.local.env`, including datasource and SMTP settings.
3. Compiles the executable JAR if not already built.
4. Starts the application with `--spring.profiles.active=postgres`.

> **Note:** The `postgres.local.env` file is git-ignored for security. Place your database connection string and credentials in `postgres.local.env` in this directory or the workspace root.

### Option B: Running with In-Memory H2 (Quick Local Demo)

To run the backend with an ephemeral in-memory H2 database (no external database required):

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

## Current status

Institutional mailbox verification and resource onboarding are implemented. Authentication requires working SMTP configuration; end-to-end email delivery has not been verified yet. User profile persistence and role assignment, booking, concurrency control, resource listing/editing, notifications, waitlists, pricing, and AI functionality remain future work. No Jira issues have been created or modified.

## AI Engineering Log

Meaningful AI-assisted engineering work is recorded in [AI_ENGINEERING_LOG.md](AI_ENGINEERING_LOG.md). [AGENTS.md](AGENTS.md) contains the workflow that future AI agents must follow.
