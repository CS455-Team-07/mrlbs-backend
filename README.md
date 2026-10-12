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

Authentication is disabled by default for the current project demo. The backend supplies a synthetic Demo Admin principal so the website and its API can be used without Google credentials. Before deploying publicly, set `AUTH_ENABLED=true`, configure a Google OAuth client, and use an institutional Google Workspace account. With authentication enabled, access requires a verified Google identity whose signed hosted-domain (`hd`) claim matches the configured institutional domain (`iitk.ac.in` by default).

The API exposes `GET /api/health`, `GET /api/auth/me`, `GET /api/auth/csrf`, and `POST /api/resources`. Business API endpoints require authentication except health and CSRF-token retrieval. The OAuth protocol endpoints are public so Google can complete the redirect flow. Actuator health is also exposed at `/actuator/health`. Resource onboarding records a name, type, location, optional positive capacity, and at least one booking rule. The resource and its ordered rules are stored in PostgreSQL through JPA and Flyway.

`POST /api/resources` additionally requires the authenticated Spring Security principal to have the `ROLE_ADMIN` authority. User profile creation and role assignment are tracked separately under FR-1.2 / BOOK-11.

## Prerequisites

- JDK 21
- Maven 3.9.11 (available through the included Maven Wrapper)
- PostgreSQL 14 or newer for local development

## Local setup

1. No database or Google credentials are needed for the default demo. The development profile uses an in-memory H2 database and a demo administrator identity.
2. From this directory, run `./mvnw spring-boot:run` (macOS/Linux) or `mvnw.cmd spring-boot:run` (Windows).
3. Start the frontend and open `http://localhost:5173`.
4. To use PostgreSQL instead, set the database environment variables below. To restore Google authentication, set `AUTH_ENABLED=true`, configure `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET`, and register `http://localhost:8080/login/oauth2/code/google` as an authorized redirect URI.

The development profile is active by default. It uses H2 with Hibernate schema updates for local demo data; the in-memory database resets when the backend stops. PostgreSQL deployments use Flyway migrations from `src/main/resources/db/migration`.

If you do not have an IITK Google Workspace account, local development can temporarily accept any verified Google account by setting `DEV_ALLOW_ANY_GOOGLE_DOMAIN=true` in the backend process environment. This setting only relaxes the institutional hosted-domain check while the `dev` profile is active; Google OIDC and verified-email checks remain enabled. It defaults to `false`, is ignored outside the `dev` profile, and must never be enabled in a production deployment. Restart the backend after changing it.

## PostgreSQL configuration

The local defaults expect PostgreSQL on `localhost:5432`, database `mrlbs`, and username `mrlbs`. Set a password through the environment rather than committing credentials. Create the database/user in PostgreSQL before starting the application.

## Environment variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/mrlbs` | JDBC connection URL |
| `DB_USERNAME` | `mrlbs` | Database username |
| `DB_PASSWORD` | empty | Database password |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Comma-separated allowed frontend origins for `/api/**` |
| `AUTH_ENABLED` | `false` | Enable Google OIDC authentication instead of the demo identity |
| `GOOGLE_CLIENT_ID` | not required when auth is disabled | Client ID for the Google OAuth 2.0 Web application |
| `GOOGLE_CLIENT_SECRET` | not required when auth is disabled | Client secret for the Google OAuth 2.0 Web application |
| `INSTITUTIONAL_DOMAIN` | `iitk.ac.in` | Required Google Workspace hosted domain |
| `DEV_ALLOW_ANY_GOOGLE_DOMAIN` | `false` | Local development only: accept a verified Google identity without the institutional hosted-domain claim when the `dev` profile is active |
| `FRONTEND_BASE_URL` | `http://localhost:5173` | Frontend URL for returning after OAuth login |

The demo identity has administrator access and is suitable only for a controlled project demo. Do not expose the default no-auth configuration publicly. For production, set `AUTH_ENABLED=true`, register the HTTPS backend callback URI with Google, and configure OAuth credentials through the deployment secret manager. The Google authorization request includes the configured `hd` value to guide account selection, while the backend validates the signed ID token claims.

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
2. Automatically loads environment variables from `postgres.local.env` (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`).
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

Institutional Google OIDC authentication and resource onboarding are implemented. User profile persistence and role assignment, booking, concurrency control, resource listing/editing, notifications, waitlists, pricing, and AI functionality remain future work. No Jira issues have been created or modified.

## AI Engineering Log

Meaningful AI-assisted engineering work is recorded in [AI_ENGINEERING_LOG.md](AI_ENGINEERING_LOG.md). [AGENTS.md](AGENTS.md) contains the workflow that future AI agents must follow.
