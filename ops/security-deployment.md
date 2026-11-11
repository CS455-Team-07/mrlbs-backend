# Production security configuration

This applies to BOOK-117, BOOK-118, and BOOK-119. The `postgres` profile uses verified sessions, requires HTTPS for application requests, and validates a pre-provisioned PostgreSQL schema using a restricted application login.

## Transport and identity

- Serve the SPA over HTTPS and build it with an HTTPS API URL or the same-origin `/api` path. Use same-site frontend and API hostnames when possible. For an intentional cross-site deployment, configure `SESSION_COOKIE_SAME_SITE=none` and confirm the supported browsers allow the session cookie.
- Start the backend with the `postgres` profile and enable its HTTPS listener with `SERVER_SSL_ENABLED=true`. Mount the certificate or keystore and configure `SERVER_SSL_KEY_STORE`, its type, and its password through deployment secrets. Point health probes to HTTPS. A proxy must pass TLS through or establish a separate verified TLS connection to the backend. Use the exact public HTTPS frontend URL and origins in `FRONTEND_BASE_URL` and `CORS_ALLOWED_ORIGINS`.
- The backend ignores forwarded headers in this profile. If a proxy shares one source address across clients, enforce client-IP limits at the edge as well. Backend booking limits still use the verified account.
- Set `SPRING_DATASOURCE_URL` with `sslmode=verify-full` and a trusted CA. Configure the PostgreSQL server or managed service to reject plaintext connections and use password or certificate authentication for allowed connections. Require SMTP STARTTLS with hostname verification, an `amqps://` RabbitMQ URI, and HTTPS Gemini and telemetry URLs.
- OTP sign-in verifies every production session, including returning members and scoped admins. The Google Workspace sign-in path keeps its institutional identity check. The local demo is available only under `dev` with `AUTH_ENABLED=false`, on the loopback listener.

## PostgreSQL access

Create a dedicated database with a migration owner and a separate runtime login. Give the runtime login a distinct secret, no memberships or administrative attributes, and no object ownership. Run database maintenance as the migration owner over TLS. Never use its credentials as the application's runtime credentials.

For an empty database, run `psql ... -f ops/bootstrap-postgres.sql`. It applies the existing SQL in dependency order, including the historical pairs that reuse version numbers. For an existing database, inspect its schema and apply the required owner migrations, including the idempotent V13 confidence-score migration where applicable. The bootstrap script is for an empty database only.

As the database and table owner, run `psql ... -v runtime_role=mrlbs_runtime -f ops/restrict-postgres.sql`. This removes public access to the dedicated database and grants the runtime role only its current application table and sequence permissions. Grant additional access explicitly when adding future tables. The application validates the schema and checks both connection encryption and runtime role privileges at startup. If previous database credentials were exposed, rotate them before rollout.

## Audit logs and limits

The `mrlbs.security.audit` logger records the request ID, operation, status, source address, and a hashed authenticated account identifier. It excludes request bodies, session cookies, and verification codes. Restrict the platform log viewer and any log export to the operations role. The app exposes health and denies access to other actuator endpoints.

| Limit | Configuration | Default per minute |
| --- | --- | ---: |
| Sign-in, code endpoints and OAuth entry/callback, per source address | `mrlbs.security.auth-per-minute` | 30 |
| Authenticated member profile bootstrap, per account | `mrlbs.security.auth-per-minute` | 30 |
| Code sends, per email | `mrlbs.security.otp-sends-per-minute` | 3 |
| Code verification attempts, per email | `mrlbs.security.otp-checks-per-minute` | 5 |
| Booking submission, draft creation and confirmation combined, per verified account | `mrlbs.security.booking-per-minute` | 30 |

Excess requests return HTTP 429 and `Retry-After`. Limits are kept in a bounded in-memory store per backend instance. In a replicated deployment, configure shared gateway limits when an instance-independent quota is required.

## Verification on 2026-11-06

Ran the packaged backend with the `postgres` profile and the built SPA over HTTPS. Used PostgreSQL 16 with TLS and the runtime grants above, a local SMTP server that accepted delivery only after STARTTLS, and Chrome with independent sessions for two members and two scoped admins.

- Verified mailbox sign-in and returning-user verification, secure session cookies, browser reload and logout, and rejection of a replayed session cookie.
- Searched the catalog and availability, created and confirmed a draft in the browser, checked private history, cancelled a booking, and accepted a waitlist offer as its owner. Another member could neither confirm the draft nor cancel the booking, accept the offer, or mark the notification read.
- Created resources as the correct scoped admin, rejected a different scope, approved a booking from the admin UI, and verified rejection, booking history, and the member inbox.
- Verified the authentication and booking limits returned 429 with a retry header. Forwarded addresses did not bypass the limit. Audit output showed throttling and operations-access denial without raw email, cookies, or codes.
- Verified PostgreSQL negotiated TLS 1.3. Plaintext connections, runtime schema creation, and an unrelated database login were denied. The backend refused startup with a schema-owner login or weaker PostgreSQL TLS mode. A plaintext application request received 403 even with spoofed proxy headers, and an untrusted browser origin received 403.
- Verified the opt-in loopback demo login separately. The automated checks passed 159 backend regression tests, two additional security tests, and 46 frontend tests. The backend run excluded the two live RabbitMQ classes.

Google Workspace, RabbitMQ, and hosted environments were outside this local run. Production log-viewer permissions and the live deployment certificate chain must be verified by the deployment operator.
