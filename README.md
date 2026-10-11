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

The backend authenticates users with Google OAuth 2.0 / OpenID Connect. A successful sign-in requires a verified Google identity whose signed hosted-domain (`hd`) claim matches the configured institutional domain. The initial domain is `iitk.ac.in`.

The API exposes `GET /api/health`, `GET /api/auth/me`, `GET /api/auth/csrf`, and `POST /api/resources`. Business API endpoints require authentication except health and CSRF-token retrieval. The OAuth protocol endpoints are public so Google can complete the redirect flow. Actuator health is also exposed at `/actuator/health`. Resource onboarding records a name, type, location, optional positive capacity, and at least one booking rule. The resource and its ordered rules are stored in PostgreSQL through JPA and Flyway.

`POST /api/resources` additionally requires the authenticated Spring Security principal to have the `ROLE_ADMIN` authority. User profile creation and role assignment are tracked separately under FR-1.2 / BOOK-11.

## Prerequisites

- JDK 21
- Maven 3.9.11 (available through the included Maven Wrapper)
- PostgreSQL 14 or newer for local development

## Local setup

1. Create a PostgreSQL database and user (or use an existing local PostgreSQL instance).
2. Create a Google OAuth 2.0 Web application client. Add `http://localhost:8080/login/oauth2/code/google` as an authorized redirect URI.
3. Set the database and Google OAuth environment variables described below.
4. From this directory, run `./mvnw spring-boot:run` (macOS/Linux) or `mvnw.cmd spring-boot:run` (Windows).
5. Open `http://localhost:5173/login` and choose **Continue with Google**.

The development profile is active by default. Hibernate schema changes remain disabled (`ddl-auto` is `none`); Flyway applies versioned migrations from `src/main/resources/db/migration`.

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
| `GOOGLE_CLIENT_ID` | required | Client ID for the Google OAuth 2.0 Web application |
| `GOOGLE_CLIENT_SECRET` | required | Client secret for the Google OAuth 2.0 Web application |
| `INSTITUTIONAL_DOMAIN` | `iitk.ac.in` | Required Google Workspace hosted domain |
| `DEV_ALLOW_ANY_GOOGLE_DOMAIN` | `false` | Local development only: accept a verified Google identity without the institutional hosted-domain claim when the `dev` profile is active |
| `FRONTEND_BASE_URL` | `http://localhost:5173` | Frontend URL for returning after OAuth login |

For production, register the HTTPS backend callback URI with Google and configure the OAuth client credentials through the deployment secret manager. The Google authorization request includes the configured `hd` value to guide account selection, but access is granted only after the backend validates the signed ID token claims.

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

```bash
./mvnw spring-boot:run
```

To build the executable JAR:

```bash
./mvnw clean package
```

## How to run tests

```bash
./mvnw test
```

Tests use the `test` profile and an in-memory H2 database, so they do not require PostgreSQL.

## Current status

Institutional Google OIDC authentication and resource onboarding are implemented. User profile persistence and role assignment, booking, concurrency control, resource listing/editing, notifications, waitlists, pricing, and AI functionality remain future work. No Jira issues have been created or modified.

## AI Engineering Log

Meaningful AI-assisted engineering work is recorded in [AI_ENGINEERING_LOG.md](AI_ENGINEERING_LOG.md). [AGENTS.md](AGENTS.md) contains the workflow that future AI agents must follow.
