# Multi-Resource Lab Booking System (MRLBS) Backend

## Purpose

This repository contains the initial backend scaffold for the CS455 Software Engineering project. The backend will provide a REST API for the MRLBS; the React and TypeScript frontend is maintained separately.

## Technology stack

- Java 21
- Spring Boot 4.1.1
- Maven
- Spring Web MVC, Spring Data JPA, Bean Validation, and Spring Boot Actuator
- PostgreSQL
- Spring Boot Test (with H2 for isolated tests)

## Architecture overview

The application uses a standard Maven source layout and a layered package structure under `com.mrlbs`. Web controllers expose REST endpoints, configuration is centralized, and persistence dependencies are present for future repository and entity work. Placeholder packages reserve space for authentication and authorization, booking, resource management, notifications, and AI integration. The frontend is a separate React and TypeScript repository. Future AI processing is expected to use asynchronous workflows.

The initial API endpoint is `GET /api/health`. Actuator health is also exposed at `/actuator/health`.

## Prerequisites

- JDK 21
- Maven 3.9.11 (available through the included Maven Wrapper)
- PostgreSQL 14 or newer for local development

## Local setup

1. Create a PostgreSQL database and user (or use an existing local PostgreSQL instance).
2. Set the database environment variables described below.
3. From this directory, run `./mvnw spring-boot:run` (macOS/Linux) or `mvnw.cmd spring-boot:run` (Windows).
4. Check `http://localhost:8080/api/health` for `{"status":"UP"}`.

The development profile is active by default. The initial scaffold does not create or validate application tables (`ddl-auto` is `none`); no application schema is included yet.

## PostgreSQL configuration

The local defaults expect PostgreSQL on `localhost:5432`, database `mrlbs`, and username `mrlbs`. Set a password through the environment rather than committing credentials. Create the database/user in PostgreSQL before starting the application.

## Environment variables

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/mrlbs` | JDBC connection URL |
| `DB_USERNAME` | `mrlbs` | Database username |
| `DB_PASSWORD` | empty | Database password |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000,http://localhost:5173` | Comma-separated allowed frontend origins for `/api/**` |

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

Initial scaffold only. No booking, concurrency control, authentication, resource management, notifications, waitlists, pricing, or AI functionality has been implemented, and no Jira issues have been created or modified.
