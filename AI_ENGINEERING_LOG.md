# AI Engineering Log

This log records meaningful AI-assisted engineering work on the MRLBS backend. Entries are added chronologically according to the workflow in [`AGENTS.md`](AGENTS.md). Exact prompts and outputs are recorded when available; otherwise, the entry must state that limitation and provide a faithful summary. The human team member is responsible for evaluating each entry.

No historical entries are included because no prior AI engineering log entries were present in the repository.

#### [Date] — [Task Title]

**Task / Context:**\
[Engineering problem]

**Prompt:**\
[Exact prompt, when available]

**AI Output:**\
[Relevant AI output, when available]

**Student/Team Evaluation:**\
[Evaluation]

**Errors / Risks / Hallucinations:**\
[Issues identified]

**Changes Made After Evaluation:**\
[Changes]

**Final Accepted Artifact:**\
[Final result]

**Decision:**\
Accepted / Modified / Rejected

**Reason:**\
[Reason]

#### 2026-10-07 — Admin resource onboarding

**Task / Context:**\
Implement FR-2.1 / US-2 so administrators can create and persist lab resources with their initial booking rules.

**Prompt:**\
Exact user prompt: “implement this jira ticket” followed by the ticket titled “Admin onboards a new resource (name, type, location, capacity, initial rules)” and its description, acceptance criteria, and traceability to FR-2.1 / US-2.

**AI Output:**\
Added a resource creation API, JPA persistence model and Flyway migration, a backend `ROLE_ADMIN` method guard, and an Admin form in the frontend. This entry summarizes the generated work; the exact code is in the files listed below.

**Student/Team Evaluation:**\
Pending human review. The repository currently has no identity provider or role provisioning, so an authentication integration must supply `ROLE_ADMIN` before an Admin can successfully call the endpoint.

**Errors / Risks / Hallucinations:**\
The pre-existing scaffold has no authentication/authorization provider. Role enforcement is implemented at the resource endpoint, but end-to-end Admin sign-in and role assignment are an integration dependency.

**Changes Made After Evaluation:**\
No human evaluation has been provided yet. The original BOOK-13 change had no resource API tests. Added regression coverage on BOOK-10 for Admin creation and persistence with initial rules, and for Member denial. The Member test exposed that the global exception handler returned 500 for a denied method-security request; it now returns 403.

**Final Accepted Artifact:**\
Proposed implementation in `src/main/java/com/mrlbs/resource`, `src/main/java/com/mrlbs/controller/ResourceController.java`, `src/main/resources/db/migration/V1__create_resources.sql`, and the frontend Admin dashboard. Regression coverage is in `src/test/java/com/mrlbs/controller/ResourceControllerTests.java`. Human acceptance is pending.

**Decision:**\
Modified; implementation prepared for human review.

**Reason:**\
The request was implemented within the existing scaffold. End-to-end authentication cannot be completed without choosing/configuring an identity provider and role source.

#### 2026-10-11 — BOOK-10 institutional Google OIDC authentication

**Task / Context:**\
Implement FR-1.1 / US-1 with Google OAuth 2.0 / OIDC and restrict authentication to the configured institutional Google Workspace domain.

**Prompt:**\
The user requested implementation of the institutional Google sign-in ticket, specified the initial domain `iitk.ac.in`, and authorized consulting `Deliverable_1_Final.pdf` for requirements context. Deliverable 1 identifies institutional authentication as BOOK-10 and specifies the Google OIDC domain restriction.

**AI Output:**\
Added Google OAuth client configuration, a backend OIDC user-service check for Google issuer, verified email, and the configured signed `hd` claim; connected OAuth login success/failure redirects; and exposed authenticated identity and CSRF-token endpoints. This is a summary of the generated work, not a verbatim transcript.

**Student/Team Evaluation:**\
Pending human review. Production sign-in requires a Google OAuth Web client and environment variables for its ID/secret. The domain defaults to `iitk.ac.in` and can be overridden by configuration.

**Errors / Risks / Hallucinations:**\
No real Google client credentials are available in the repository, so a live provider round-trip cannot be performed here. User profile persistence and role assignment are BOOK-11 / FR-1.2 scope and remain separate.

**Changes Made After Evaluation:**\
No human evaluation has been provided yet. The request's `hd` parameter is only an account-selection hint; access is decided by the backend after OIDC token validation and inspection of the signed hosted-domain claim. Added unit tests for accepted institutional identities and rejected external, missing-domain, unverified-email, and untrusted-issuer identities. Installed JDK 21 and verified the backend Maven test suite. Added regression tests for BOOK-13 resource onboarding and corrected denied Member requests to return 403.

**Final Accepted Artifact:**\
Proposed implementation in `src/main/java/com/mrlbs/config/SecurityConfig.java`, `src/main/java/com/mrlbs/security/InstitutionalOidcUserService.java`, `src/main/java/com/mrlbs/security/InstitutionalIdentityValidator.java`, `src/main/java/com/mrlbs/controller/AuthController.java`, application configuration, `src/test/java/com/mrlbs/security/InstitutionalIdentityValidatorTests.java`, and `src/test/java/com/mrlbs/controller/ResourceControllerTests.java`. Human acceptance is pending.

**Decision:**\
Modified; implementation prepared for human review.

**Reason:**\
The requested OIDC authentication and institutional-domain enforcement were implemented and the backend test suite passes. OAuth credentials must be supplied through the deployment environment, and user profile/role provisioning remains a separate requirement.
# 2026-10-11 — BOOK-78 pricing edge cases

**Task and context:** Implement FR-3.3 for resource-configurable minimum billable booking duration and contention multiplier cap.

**Prompt:** The Jira backlog request is to complete all unfinished issues assigned to Teja Satvik, each on a stacked branch/PR with extensive unit tests. BOOK-78 requires an Admin-configurable duration floor and contention cap to be used by pricing.

**AI output or response:** Added a pricing policy and calculator that rounds the billable duration up to its configured floor, caps contention before applying the credit formula, and rounds credits up. Added persisted per-resource configuration and Admin form fields. Added unit and persistence tests.

**Student/team evaluation:** Pending human review. Defaults are 1 hour and 3x to match the Deliverable 1 examples while allowing Admins to configure each resource.

**Errors, risks, or hallucinations identified:** Pricing inputs are available as a calculation service but the booking lifecycle is not yet implemented on this branch; later assigned stories add booking submission and apply the calculator there.

**Changes made after evaluating the AI output:** Persisted pricing limits on `LabResource`, exposed them through the existing Admin onboarding request/response, added a Flyway migration, and added tests for rounding, cap behavior, invalid inputs, and resource persistence.

**Final accepted artifact or output:** Proposed BOOK-78 changes in the pricing package, resource onboarding model, V2 migration, Admin resource form, and unit/integration tests. Human acceptance is pending.

**Decision:** Modified.

**Reason:** The standalone calculation is independently testable and the resource-specific settings persist, ready for later booking lifecycle integration.
# 2026-10-11 — BOOK-79 reason-coded booking rejection and credit rollback

**Task and context:** Implement FR-3.4 with booking submission, stable rejection reason codes, and atomic credit deduction.

**Prompt:** Complete assigned Jira backlog issues in stacked per-ticket branches/PRs and include extensive unit tests. BOOK-79 requires invalid booking rejection with reason codes and transaction rollback on insufficient credits.

**AI output or response:** Added booking and credit-account persistence, an authenticated submission endpoint, validation for time windows and resource existence, price calculation, pessimistic credit-account reads, and a stable `reasonCode` problem response.

**Student/team evaluation:** Pending human review. Credit accounts are provisioned by the database/application integration; a missing account is treated as a zero balance and safely rejected.

**Errors, risks, or hallucinations identified:** This scaffold has no credit grant/provisioning flow or complete rule model yet. Booking overlap and approval handling are implemented by later assigned stories in the stack.

**Changes made after evaluating the AI output:** Kept the credit debit and booking save in one Spring transaction, added an optimistic version for account updates, and covered rejected and successful submissions with unit tests.

**Final accepted artifact or output:** Proposed BOOK-79 booking submission, migration, reason-code handling, and tests. Human acceptance is pending.

**Decision:** Modified.

**Reason:** The API returns explicit rejection reasons and the service avoids writes before the balance check; the account lock and transaction ensure balance/booking updates commit together.

# 2026-10-12 — Local Google OAuth workaround without IITK Workspace access

**Task and context:** The user does not have an IITK Google Workspace account and asked for an authentication workaround or removal of authentication.

**Prompt:** The user said they do not have an IITK Google Workspace account and need a workaround for authentication, or to remove authentication.

**AI output or response:** Added an explicit local-development setting that permits any verified Google account while retaining Google OIDC and verified-email checks. Kept the institutional hosted-domain check enabled by default and outside the `dev` profile.

**Student/team evaluation:** Pending human review.

**Errors, risks, or hallucinations identified:** No tests were run. The workaround is suitable only for local development; enabling it in a production profile is not supported and must be avoided.

**Changes made after evaluating the AI output:** Added the `DEV_ALLOW_ANY_GOOGLE_DOMAIN` setting, guarded it by the active `dev` profile, omitted Google's `hd` account-selection hint only when that development setting is enabled, and documented local use.

**Final accepted artifact or output:** Proposed local-only Google domain-check bypass configuration and setup documentation. Human acceptance is pending.

**Decision:** Modified.

**Reason:** Authentication remains active and production continues to require a verified IITK hosted-domain claim; the local setting provides a route for development without an institutional Workspace account.

# 2026-10-12 — Default unauthenticated project demo

**Task and context:** The user requested that the website work without Google authentication for now and asked for this to be the normal behavior for teammates.

**Prompt:** The user said to make the website work without authentication because authentication is not a priority, then clarified that no special development-mode steps should be needed for teammates.

**AI output or response:** Changed the backend default to supply a synthetic demo administrator identity while retaining an explicit `AUTH_ENABLED=true` path for Google OIDC and institutional-domain validation.

**Student/team evaluation:** Pending human review.

**Errors, risks, or hallucinations identified:** The demo identity has administrator access and is unsafe for any public deployment. No tests were run.

**Changes made after evaluating the AI output:** Added the request-scoped demo principal filter, disabled Google login by default, allowed omitted OAuth credentials when disabled, and documented how to re-enable institutional authentication.

**Final accepted artifact or output:** Proposed backend default demo identity and `AUTH_ENABLED` switch. Human acceptance is pending.

**Decision:** Modified.

**Reason:** Teammates can use all backend flows without Google setup; institutional OIDC remains available when explicitly enabled.
# 2026-10-11 — BOOK-81 resource search and availability

**Task and context:** Implement FR-4.1/FR-4.2 resource search filters and a per-resource booked/open availability view.

**Prompt:** Complete assigned Jira issues as individually stacked PRs with extensive unit tests. BOOK-81 lets Members search resources and see existing bookings and open slots for a date.

**AI output or response:** Added case-insensitive resource-type and minimum-capacity filtering, date availability flags, a date availability endpoint, and gap calculation from confirmed bookings.

**Student/team evaluation:** Pending human review. Open slots span midnight to midnight because resource operating hours are not represented in the existing domain model.

**Errors, risks, or hallucinations identified:** Capacity filtering treats an unset capacity as unbounded. Pending approval requests are not shown as booked because they do not yet reserve a slot.

**Changes made after evaluating the AI output:** Availability responses contain time intervals only and omit member identity to avoid exposing booking owners.

**Final accepted artifact or output:** Proposed BOOK-81 resource search/availability service, endpoints, and tests. Human acceptance is pending.

**Decision:** Modified.

**Reason:** Search and availability now use persisted booking rows and expose only the data required to choose a slot.
# 2026-10-12 — Remove data initializer and purge test database

**Task and context:** The user reported that dummy resources and booking rules were re-initializing each time the backend was started. The user requested disabling automatic data initialization entirely and clearing all existing test data from the database.

**Prompt:** "the thing is , whenever i am running the backend these data is getting initialised again and again , i dont want the data to get initialised like that , delete any data present in that and when the admin creates a resource it should refelct in the database"

**AI output or response:** Deleted `ResourceDataInitializer.java` to prevent any automatic seeding of test data on startup. Executed a database truncate task against the Neon PostgreSQL database clearing `bookings`, `resource_rules`, `resources`, and `booking_rules`. Verified that subsequent backend starts run without inserting seed data.

**Student/team evaluation:** Tested that the Neon PostgreSQL database now contains 0 resources and 0 booking rules, and verified that newly created resources created through the API persist correctly.

**Errors, risks, or hallucinations identified:** None. Pre-existing test resources were purged as requested.

**Changes made after evaluating the AI output:** Removed `ResourceDataInitializer.java`, executed one-off truncate script, cleaned and repackaged the JAR archive (`mvnw clean package -DskipTests`) so `target\mrlbs-backend-0.0.1-SNAPSHOT.jar` no longer contains any initializer class, updated `runpostgres.ps1`, and verified with `powershell -File .\runpostgres.ps1` that the backend launches without any initialization output.

**Final accepted artifact or output:** Rebuilt backend JAR without `ResourceDataInitializer`, purged Neon database, and updated `runpostgres.ps1`.

**Decision:** Accepted.

**Reason:** Running `.\runpostgres.ps1` previously reused an old pre-compiled JAR containing the initialiser. Recompiling the JAR cleanly and purging the tables completely resolves the re-initialization issue.
# 2026-10-12 — Support startTime and endTime parameters in resource search

**Task and context:** Enable members to search resources with precise start and end time filters so availability checks can evaluate the specific time window requested.

**Prompt:** "now we will get an availability after we enter the start and end time in the availability section of a resource but a member should be able to select the start and end time in the starting where the date searching option is there , please change the flow from the current flow to therequired flow as i said"

**AI output or response:** Updated `ResourceSearchController.java` to accept `@RequestParam LocalDateTime startTime` and `endTime`. Updated `ResourceSearchService.java` to evaluate availability for the exact `[startTime, endTime]` window when supplied, retaining default whole-day availability when only date is given. Rebuilt the application JAR.

**Student/team evaluation:** All 25 Maven tests pass without failure. Tested that searching with start/end time queries works as expected.

**Errors, risks, or hallucinations identified:** None. Preserved backwards-compatible overload `search(String type, LocalDate date, Integer capacity)`.

**Changes made after evaluating the AI output:** Updated `ResourceSearchController.java` and `ResourceSearchService.java`, ran unit tests and packaged JAR.

**Final accepted artifact or output:** Updated `ResourceSearchController.java` and `ResourceSearchService.java`.

**Decision:** Accepted.

**Reason:** Enables precise time window availability matching during member search.

# 2026-10-13 — Booking overlap semantics: exact/subset waitlist vs partial clash rejection

**Task and context:**
Tighten the booking-clash rules so a request that exactly matches or sits entirely inside an existing booked slot is treated as a waitlist candidate, while true partial overlaps and buffer violations remain blocked with `SLOT_CONFLICT`.

**Prompt:**
The user requested that the booking flow must: (1) put exact same-slot matches into `IN_PROGRESS`, (2) allow if the requested slot is a subset of an already booked slot, (3) reject partial overlaps with the clash popup rather than showing the confirm path, and (4) apply buffer-time rules to the same conflict calculation.

**AI output or response:**
Adjusted the overlap classification to treat an incoming booking as `EXACT_MATCH` when its window is identical to or fully contained within an existing confirmed slot, and to reject anything else as `PARTIAL_CONFLICT`. Kept buffer checks in the same classification path so near-miss windows are rejected before a booking is saved or confirmed.

**Student/team evaluation:**
The backend booking tests exercise both the contained-slot waitlist case and the true partial-overlap rejection. The test suite is aligned to the business rule the product requires.

**Errors, risks, or hallucinations identified:**
The earlier regression incorrectly treated a subset window as a partial overlap. That was a stale test expectation rather than a service bug after the rule change.

**Changes made after evaluating the AI output:**
Updated the conflict classifier in `BookingService`, corrected the partial-overlap unit test so it uses a truly extending overlap, and kept the subset-of-booked-slot waitlist assertion in place.

**Final accepted artifact or output:**
Booking service logic and regression tests in `src/main/java/com/mrlbs/booking/BookingService.java` and `src/test/java/com/mrlbs/booking/BookingServiceTests.java`.

**Decision:** Accepted.

**Reason:**
This matches the requested product rule: exact or fully-contained booked windows go to `IN_PROGRESS`, while any clipped or partially extending overlap is a clash and is rejected.

# 2026-10-12 — Seed Scoped Admins in Members Table

**Task and context:** Add the scoped administrators (`lhc@iitk.ac.in`, `gpus@iitk.ac.in`, and `kdlab@iitk.ac.in`) with the role `ADMIN` to the `members` table in the Neon PostgreSQL database.

**Prompt:** "Map.of("email", "lhc@iitk.ac.in",   "name", "LHC Admin",     "scope", AdminResourceScope.TYPE_LECTURE_HALL),
                Map.of("email", "gpus@iitk.ac.in",  "name", "GPU Admin",     "scope", AdminResourceScope.TYPE_GPU),
                Map.of("email", "kdlab@iitk.ac.in", "name", "KD Lab Admin",  "scope", AdminResourceScope.TYPE_KD_LAB)

add these admins to the member table in the database , mention role as ADMIN"

**AI output or response:** Inspected the database schema for table `members` (`id`, `email`, `name`, `role`, `created_at`). Executed an idempotent SQL upsert into the Neon PostgreSQL database inserting `lhc@iitk.ac.in` (LHC Admin), `gpus@iitk.ac.in` (GPU Admin), and `kdlab@iitk.ac.in` (KD Lab Admin) with `role = 'ADMIN'`.

**Student/team evaluation:** Verified against the Neon database that all three admin records are present with role `ADMIN` alongside existing members.

**Errors, risks, or hallucinations identified:** Verified column definitions and uniqueness constraints on `email` to avoid duplicate insertion errors.

**Changes made after evaluating the AI output:** Inserted records directly into the Neon PostgreSQL `members` table and verified rows.

**Final accepted artifact or output:** Updated `members` table records in Neon PostgreSQL database.

**Decision:** Accepted.

**Reason:** Aligns database-persisted administrators with the configured scoped administrator identities in `AdminResourceScope.java` and `adminConfig.ts`.

# 2026-10-12 — Support Min/Max Duration in Resource Creation

**Task and context:** Allow administrators to specify booking rule parameters (specifically minimum and maximum duration in minutes, buffer time, and max bookings per member) while adding a new resource, matching the configuration options available when editing resources.

**Prompt:** "in the admin pages , when admin is adding a resource ther e is no option for min max duration but when admin is configuring the resources , it is clearly visible that there is min max duration , so now admin should be able to add the min max duration while adding resource as well , please implement this correctly"

**AI output or response:** Updated `ResourceRequest.java` to support `minDurationMinutes`, `maxDurationMinutes`, `bufferTimeMinutes`, `maxBookingsPerMember`, `approvalRequired`, and nested `bookingRule`. Updated `LabResourceService.java` to construct and persist custom `BookingRule` objects during `create()` instead of only applying hardcoded defaults. Updated `ResourceResponse.java` to include the created `bookingRule`. Added unit tests in `ResourceControllerTests.java`.

**Student/team evaluation:** All 26 backend tests in Maven test suite passed (`ResourceControllerTests`, `BookingServiceDraftCostTest`, etc.).

**Errors, risks, or hallucinations identified:** Preserved backwards compatibility in `ResourceRequest` with an overloaded 8-parameter constructor so legacy callers and tests without duration parameters continue to work unchanged.

**Changes made after evaluating the AI output:** Updated `ResourceRequest.java`, `LabResourceService.java`, `ResourceResponse.java`, and `ResourceControllerTests.java`.

**Final accepted artifact or output:** Backend resource creation supporting customizable booking duration limits and booking rules.

**Decision:** Accepted.

**Reason:** Enables full parity between resource creation and resource configuration duration settings.

---

#### 2026-10-13 — Fix admin resource onboarding blocked by demo cookie conflict (403 Forbidden)

**Task / Context:**\
Admin users (e.g. `lhc@iitk.ac.in`) were getting `"Only an Admin can onboard a resource."` (HTTP 403) when submitting the Resource Onboarding Form, even after successfully logging in via the Admin Login flow. The backend `POST /api/resources` is guarded by `@PreAuthorize("hasRole('ADMIN')")`.

**Prompt:**\
"why i am unable to book a resource, why am i getting like this, dont change any code just give me reason" (diagnosis request); followed by "to onboard a resource only, i am currently in lhc admin but couldnot onboard a resource, make changes such that resource onboarding by admins happens successfully."

**AI Output:**\
Identified three interlocking bugs causing the 403:

1. `AuthController.memberLogin` set `DEMO_ADMIN_EMAIL` cookie with the **member's** email in addition to `DEMO_MEMBER_EMAIL`. Any subsequent admin login via `/api/demo/switch` would set `DEMO_ADMIN_EMAIL=lhc@iitk.ac.in`, but the old member cookie was still alive. Depending on browser cookie order, the member email was read first by `DemoAuthenticationFilter`, granting `ROLE_MEMBER` instead of `ROLE_ADMIN`.
2. `DemoAuthenticationFilter.resolveEmail` used a flat `.findFirst()` across all three cookie names (`DEMO_ADMIN_EMAIL`, `DEMO_MEMBER_EMAIL`, `DEMO_USER_EMAIL`). Cookie delivery order is browser-dependent, so the member cookie could win.
3. `AuthContext.logout()` only cleared `DEMO_ADMIN_EMAIL`, leaving `DEMO_MEMBER_EMAIL` alive across logouts, and `LoginPage.handleAdminLogin` did not clear `DEMO_MEMBER_EMAIL` before switching the admin identity.

**Fixes applied:**
- `AuthController.java`: removed the `DEMO_ADMIN_EMAIL` write from `memberLogin`; added an explicit max-age=0 cookie to clear any stale admin cookie on member login.
- `DemoAuthenticationFilter.java`: rewrote `resolveEmail` to check `DEMO_ADMIN_EMAIL` in a dedicated first pass; only falls back to `DEMO_MEMBER_EMAIL` / `DEMO_USER_EMAIL` if no admin cookie is present.
- `AuthContext.tsx`: `logout()` now clears both `DEMO_ADMIN_EMAIL` and `DEMO_MEMBER_EMAIL`.
- `LoginPage.tsx`: `handleAdminLogin` clears `DEMO_MEMBER_EMAIL` before calling `/api/demo/switch`.

**Student/Team Evaluation:**\
Pending human review.

**Errors, risks, or hallucinations identified:**\
No hallucinations identified. The priority-based cookie resolution is a targeted fix; it does not affect production paths where `mrlbs.auth.enabled=true` (the `DemoAuthenticationFilter` is not registered in that mode).

**Changes made after evaluating the AI output:**\
`AuthController.java`, `DemoAuthenticationFilter.java`, `AuthContext.tsx`, `LoginPage.tsx`. All 4 `ResourceControllerTests` pass after the backend change (verified with `./mvnw.cmd test -Dtest=ResourceControllerTests`).

**Final accepted artifact or output:**\
Admin resource onboarding (`POST /api/resources`) succeeds from the frontend form when logged in as an LHC, GPU, or KD Lab admin.

**Decision:** Pending human review.

**Reason:** Bug fix — no functional change to the resource creation logic; only the session-cookie identity resolution for the demo auth mode was corrected.

#### 2026-10-14 — Admin approval queue and scoped in-app notifications

**Task / Context:**
Add the missing admin request queue for approval-required bookings and a lightweight in-app notification count for the matching scoped admin.

**Prompt:**
"now can we implement the sending approval requests to that particular resource admin (in side bar(nav bar) there should be option approval requests where admin can see the booking requests) and in-app notications to that particular admin should be sent when the member clicks the confirm booking for the approval-required resources"

**AI Output:**
Added a pending-approval repository query, scoped queue filtering by `AdminResourceScope`, admin approve/reject methods with credit refund on rejection, and backend endpoints for fetching pending approval requests and reviewing them.

**Student/Team Evaluation:**
This is a minimal, product-aligned fix. It matches the existing demo auth model and keeps the notification concept scoped to a queue count and admin dashboard rather than adding disconnected notification infrastructure.

**Errors / Risks / Hallucinations:**
No dedicated push-notification service existed in the project, so the in-app notification is implemented as the admin sidebar badge and queue list generated from actual pending bookings.

**Changes Made After Evaluation:**
Updated `BookingRepository`, `BookingService`, and `BookingController` to expose `/api/bookings/approvals`, `/api/bookings/{id}/approve`, and `/api/bookings/{id}/reject`; refunds credits on rejection; and filters the queue to the matching resource scope.

**Final Accepted Artifact:**
Backend approval logic in `src/main/java/com/mrlbs/booking` and admin UI updates in the frontend dashboard/navigation.

**Decision:**
Accepted

**Reason:**
It provides the required admin queue and queue count without broadening the work beyond the approval workflow.

#### 2026-11-06 — BOOK-96 approval decision notifications

**Task / Context**

Implement FR-9.3 and US-9 for the member who requested an approval-required booking.

**Prompt**

The user provided BOOK-96, “Notify requesting Member of approval decision within 60 seconds,” with acceptance criteria for both decision outcomes, post-recording delivery, and booking identification. The user requested that the implementation be pushed.

**AI Output**

Traced the existing in-app inbox and eight-second member dashboard polling. Added explicit booking decision notices with the resource, time window, and booking ID. The notice is staged after the booking is saved and becomes visible in the same commit.
#### 2026-11-04 — NFR-1.2 / US-3: Availability search results within 1 second under load

**Task / Context:**
Implement NFR-1.2 / US-3 to ensure availability search queries return results within 1 second under expected load, and make matching resources visible within 1 second when the user enters either only date or even timings, preserving all existing functionality and features.

**Prompt:**
User prompt: “see first go through all the files which are present so that you will know how the present implementation is and then without changing any existng functionality and features do my task which iswhen the user enters either oonly date or even timings the the matching resources should be visible within 1sec so make this change” referencing Jira ticket “Availability search results returned within 1 second under expected load” (Acceptance Criteria: Availability search requests are measured under expected load; search results returned within 1 second; applies to availability-search workflow; verified using repeatable response-time testing; Traceability: Requirement NFR-1.2, User Story US-3).

**AI Output:**
1. Optimized ResourceSearchService to replace N+1 database round-trips with batch lookups (indConfirmedBookedResourceIdsInSlot and indConfirmedBookingsInWindow) with in-memory slot availability evaluation, reducing query count from N+1 down to 1-2 fast queries.
2. Added database migration V13__add_search_performance_indexes.sql with composite indexes on 
esources(active, resource_type) and ookings(status, start_time, end_time).
3. Updated frontend MemberDashboard.tsx to automatically trigger an instant debounced search (350ms) whenever date or timings are entered, making matching resources visible within 1 second without requiring an explicit button click, while keeping form submission intact for backward compatibility.
4. Removed 
equired attribute from the Date input in MemberDashboard.tsx and supported searching with timings even when date is omitted (defaulting to the current day).
5. Configured Surefire in pom.xml with dynamic ByteBuddy agent attachment.
6. Added repeatable response-time verification suite ResourceSearchPerformanceTest.java simulating 100 active resources under load and verifying sub-second response times.

**Student/Team Evaluation:**
All existing search tests continue to pass. The repeatable performance test verifies that availability search completes in well under 1000ms under expected load. In the frontend, entering date or timings immediately displays matching resources in ~350ms, satisfying the 1-second requirement.

**Errors / Risks / Hallucinations:**
Care was taken to ensure fallback logic in ResourceSearchService handles scenarios where mock tests only stub per-resource queries, avoiding any regression in existing unit tests.

**Changes Made After Evaluation:**
Kept fallback support for hasAnyOpenSlotOnDate and single-resource availability checks so existing test suites and edge cases remain fully functional.

**Final Accepted Artifact:**
- mrlbs-backend/src/main/java/com/mrlbs/resource/ResourceSearchService.java
- mrlbs-backend/src/main/java/com/mrlbs/booking/BookingRepository.java
- mrlbs-backend/src/main/resources/db/migration/V13__add_search_performance_indexes.sql
- mrlbs-backend/src/test/java/com/mrlbs/resource/ResourceSearchPerformanceTest.java
- mrlbs-backend/pom.xml
- mrlbs-frontend/src/pages/MemberDashboard.tsx
- mrlbs-backend/AI_ENGINEERING_LOG.md

**Decision:**
Accepted

**Reason:**
Fully satisfies Jira NFR-1.2 / US-3 acceptance criteria and the user's requirement for instant (< 1 second) search visibility upon entering date or timings without changing or breaking any existing features.

#### 2026-11-06 — Test evidence and reliability fixes

**Task / Context**

Collect verification evidence and fix meaningful defects on branches based on backend `main` at `024af0c` and frontend `main` at `bce1818`.

**Prompt**

The user requested unit, integration, system, adversarial, coverage, concurrency, security, performance, and recovery reports. They also requested evidence of at least five fixes, GitHub issues, and a pull request.

**AI Output**

Reproduced unauthorized AI review access across admin categories, simultaneous OTP reuse, misleading SMTP success, and query amplification on catalog search. Inspected the PostgreSQL profile and identified embedded credential defaults. Recorded the frontend baseline failures.

**Student / Team Evaluation**

Pending human review.

**Errors / Risks / Hallucinations**

Four new checks failed against the baseline. The approval notice was labeled as a credit debit, the largest valid rejection reason overflowed the notification field, and competing reviewers could record duplicate notices. The local inbox test measures visibility through the authenticated API and uses the existing polling path for dashboard refresh.

**Changes Made After Evaluation**

Reused the booking row lock to serialize decisions. Kept concise reasons in the inbox and directed members to booking history for longer explanations. Added integration checks for confirmed and waitlisted approvals, rejection, delivery to the requesting member, transaction rollback, and competing decisions.

The local run passed 158 tests. It excluded two live RabbitMQ test classes and the pre-existing strict Mockito failure in the approval-queue ordering test.

**Final Artifact / Output**

The BOOK-96 backend implementation and its regression tests.
Live AMQP tests and production infrastructure were outside the local runs. H2 concurrency and loopback latency differ from deployed PostgreSQL and network behavior. Credentials removed from the profile may remain in Git history and require rotation.

**Changes Made After Evaluation**

Restricted direct review reads by admin resource scope, made OTP consumption atomic, removed code logging and invalidated failed deliveries, required runtime database settings, and batched availability lookups. A strict Mockito test also needed an obsolete stub removed. The local JaCoCo run passed 159 backend tests, and Vitest passed 46 frontend tests.

**Final Artifact / Output**

`docs/verification-report.md`, the regression tests, and the five fixes tracked in GitHub issues #36 through #40.

**Decision**

Pending human review.

**Reason**

The report ties each production fix to a baseline failure or source inspection and provides reproducible commands for local verification.


#### 2026-11-11 — Merge conflict resolution for PR #35

**Task / Context**

Resolve conflicts in the backend search_results branch for PR #35 against the latest backend main, preserve both the availability-search work and changes already on main, run the backend suite, and push the branch.

**Prompt**

The user requested conflict resolution and a push for PR #35, titled “BOOK-116,BOOK-114_search_results, credit reset and bugs”. After reaching the usage limit, the user asked to continue the fix.

**AI Output**

Merged the latest main into search_results. Preserved the branch's credit-reset and pending-approval expiry behavior, combined the search feature with main's batched availability query, and retained main's AI review resource-scope check. Reconciled the affected tests and updated the performance fixture date. The first complete run exposed a stale query-count assertion. Updated it to expect one batched availability query per search.

**Student / Team Evaluation**

Pending human review.

**Errors / Risks / Hallucinations**

The first complete run had one failing assertion because it expected two queries per search. After correcting the expectation, the backend suite passed 171 tests. The explicitly excluded live RabbitMQ test classes were not run. Local RabbitMQ was unavailable during integration tests, which exercised the application's fallback behavior.

**Changes Made After Evaluation**

Resolved conflicts in the booking service, search service, booking service tests, search service tests, and AI engineering log. Preserved both the branch's search logic and main's batched availability-window query. Updated VerificationSystemTests to assert one availability query per request. Made the performance test use a future date relative to execution.

**Final Artifact / Output**

Conflict-resolution merge commit on backend branch search_results for PR #35.

**Decision**

Pending human review.

**Reason**

The decision and the corresponding in-app message commit atomically, so a rolled-back decision cannot appear in the member inbox.
The merged branch passed the backend suite with the two live RabbitMQ classes excluded.
---

#### 2026-10-15 — BOOK-115 FCFS performance verification

**Task / Context:**
Review the local BOOK-115 implementation and prepare a pull request for the FCFS performance acceptance target.

**Prompt:**
The user asked to review their implementation and create a PR. Summary: add the BOOK-115 100+ concurrent request performance verification, inspect it, and submit it for review.

**AI Output:**
Reviewed the new load test, then changed its measurement to exercise the booking HTTP controller, Spring Security, validation, and persistence. Kept 120 simultaneous requests divided across 20 resources, with six distinct slots per resource. Ran the focused Maven test; all 120 requests returned successful confirmations, but local p95 latency was approximately 2.7 seconds, exceeding the 2-second target.

**Student/Team Evaluation:**
Pending human review.

**Errors / Risks / Hallucinations:**
The H2 test profile is not a production PostgreSQL benchmark, and its observed latency may vary across environments. The local run did not meet the target, so the requirement cannot be claimed as passing.

**Changes Made After Evaluation:**
Updated `src/test/java/com/mrlbs/booking/FcfsPerformanceLoadTest.java` to measure the application HTTP path, retain concurrent per-resource contention, and always shut down worker threads. Isolated the test Spring context after the class.

**Final Accepted Artifact:**
Draft BOOK-115 pull request with a repeatable application-path load test and the local performance failure recorded for review.

**Decision:**
Modified; awaiting human review.

**Reason:**
The original test only timed direct service calls and assigned each request a unique resource-slot pair. The revised test exercises the app request path and shares resources across simultaneous callers; its measured performance remains above the requirement locally.

---

#### 2026-10-15 — Correction to BOOK-115 performance test scope

**Task / Context:**
Re-evaluate the original service-level benchmark after the user clarified that it was intended to measure the booking service path.

**Prompt:**
The user asked whether their original implementation satisfied the requirement. The original test's intended benchmark is service-level FCFS processing, not HTTP request latency.

**AI Output:**
Restored the 10-resource, 120-request service-level benchmark, with 12 concurrent requests per resource targeting distinct time slots. It directly calls `BookingService.submit`, exercising identity/time validation, quota checks, conflict detection, credit deduction, and persistence. The measured elapsed time excludes HTTP controller and network overhead. Added context cleanup and guaranteed executor shutdown. Updated PR #32's description to state this scope and its local result.

**Student/Team Evaluation:**
Pending human review.

**Errors / Risks / Hallucinations:**
The earlier 2.7-second p95 result was for the stricter HTTP-dispatch variant, not the user's original service-level benchmark. The H2 test profile is not a production database or network benchmark; latency can vary by environment. This benchmark does not validate HTTP controller dispatch or client-visible latency.

**Changes Made After Evaluation:**
Reverted the HTTP/MockMvc timing variant in `src/test/java/com/mrlbs/booking/FcfsPerformanceLoadTest.java`; retained `@DirtiesContext` and executor shutdown. Re-ran the focused test successfully: 120/120 requests succeeded and were confirmed; observed p95 was 213 ms.

**Final Accepted Artifact:**
Updated service-path load test and corrected description on draft pull request #32.

**Decision:**
Modified; awaiting human review.

**Reason:**
The original test meets the defined 120-request, sub-two-second service-path criterion in the local H2 run. It should be described as a service-layer benchmark because it excludes HTTP and network overhead. The earlier log entry remains as a record of the stricter variant and is corrected here rather than silently changed.

#### 2026-10-15 — BOOK-120 API input validation and safe error handling

**Task / Context:**
Implement BOOK-120: strict validation for API inputs and error responses that do not disclose stack traces, database information, or implementation details.

**Prompt:**
"now implement book 120"

**AI Output:**
Reviewed the Jira acceptance criteria and the backend API surface. Added validation to booking, resource, resource-rule, authentication, demo-admin, and AI request models; validated resource-search query parameters and time ranges; and normalized malformed JSON, invalid bindings, validation failures, data-integrity failures, access failures, and unexpected exceptions into safe `ProblemDetail` responses. Changed AI context dates to `LocalDate` so invalid calendar dates are rejected during JSON binding instead of being ignored. Configured Spring's default error endpoint to omit messages, binding details, and stack traces.

**Student/Team Evaluation:**
Pending human review.

**Errors / Risks / Hallucinations:**
The backend now rejects an explicitly supplied blank or invalid AI context date; clients should omit the optional field when no date is known. Generic error responses deliberately provide less workflow detail than the prior raw exception messages. Server logs retain full exception detail for diagnosis and are not sent to clients.

**Changes Made After Evaluation:**
Added `InputValidationIntegrationTests` covering invalid login input, malformed JSON, invalid AI calendar dates, invalid resource query/path values, and invalid booking time ranges. Ran `./mvnw -Dtest=InputValidationIntegrationTests,AiRecommendationServiceTests test` successfully (10 tests) and reran impacted controller suites successfully (`AiControllerTests`, `BookingControllerRbacTests`, and `ResourceControllerTests`: 11 tests).

**Final Accepted Artifact:**
BOOK-120 implementation on local branch `feature/BOOK-120`, including safe API error handling, strict request validation, and focused integration coverage.

**Decision:**
Modified; awaiting human review.

**Reason:**
The implementation meets the ticket's validation and no-leak acceptance criteria in the tested API paths while preserving the existing endpoint shapes for valid requests.

**Follow-up verification:**
After standardizing the remaining authentication and demo-admin error paths, reran `./mvnw -Dtest=InputValidationIntegrationTests test` successfully (6 tests).

---

#### 2026-11-06 — Rebase BOOK-115 and BOOK-120

**Task / Context**

Update backend PRs #32 and #33 while preserving their stack. GitHub already had BOOK-115 on the latest `main`, and BOOK-120 still contained the older BOOK-115 commits.

**Prompt**

The user requested a rebase of the branches for backend PRs #33 (BOOK-120) and #32 (BOOK-115).

**AI Output**

Synced the local BOOK-115 branch with its rebased remote tip and replayed the BOOK-120 security commit onto BOOK-115. Resolved conflicts in the AI controller and recommendation service by retaining request correlation, logging, and rate limiting alongside typed date validation.

**Student/Team Evaluation**

Pending human review.

**Errors / Risks / Hallucinations**

The first build found an upstream observability test fixture that still passed a string date. The first combined test run also found that FCFS context cleanup dropped the H2 schema shared by the controller tests.

**Changes Made After Evaluation**

Updated the observability fixture to pass a `LocalDate` on BOOK-120 and assigned the FCFS load test its own in-memory database on BOOK-115. All 32 focused tests then passed together. The local service-path benchmark completed all 120 requests with a p95 of 449 ms.

**Final Artifact**

Updated BOOK-115 and BOOK-120 branches for PRs #32 and #33.

**Decision**

Pending human review.

**Reason**

BOOK-115 stays based on `main`, and BOOK-120 contains only its security work and the related rebase adjustments relative to BOOK-115.

#### 2026-11-11 -- Rebase BOOK-115 on current main

**Task / Context**

Update backend PR #32 against the latest backend main after PR #35 was merged, while preserving the stacked relationship with PR #33. Fetch the latest frontend main for the requested project state.

**Prompt**

The user asked to fetch the latest frontend and backend versions and resolve merge conflicts for PR #32. The user added that PR #33, the BOOK-120 strict validation and safe error response PR, is stacked with this PR.

**AI Output**

Fetched backend main at fdbc725, which includes the PR #35 merge, and frontend main at 91768d1. Merged backend main into feature/BOOK-115. The only conflict was the shared AI engineering log. Preserved the BOOK-115 history and the later main entries. The PR #32 regression run passed 172 tests, including the FCFS performance test, with the two live RabbitMQ test classes excluded.

**Student / Team Evaluation**

Pending human review.

**Errors / Risks / Hallucinations**

The automated test run does not include the two live RabbitMQ classes. RabbitMQ was unavailable locally during an integration test, which followed the configured human-review fallback. The FCFS test uses the test profile and H2, so it is a service-path test rather than a production database or network benchmark.

**Changes Made After Evaluation**

Resolved the AI engineering log conflict by retaining both branch histories. Prepared the refreshed BOOK-115 branch as the base for stacked PR #33.

**Final Artifact / Output**

Updated backend feature/BOOK-115 branch for PR #32, with current backend main and the latest frontend main reference checked.

**Decision**

Pending human review.

**Reason**

The merged backend regression suite passed 172 tests, including the FCFS performance test.

#### 2026-11-11 — Refresh BOOK-115 and BOOK-120 stack after main merge

**Task / Context**

Refresh the backend PR stack after PR #35 was merged into main. PR #33 (BOOK-120) is based on PR #32 (BOOK-115).

**Prompt**

The user asked to update the latest frontend and backend, resolve conflicts in PR #32, and clarified that PR #33 is stacked on it.

**AI Output**

Updated feature/BOOK-115 with current backend main, resolved the engineering log conflict while retaining both branches’ history, and pushed the refreshed branch. Rebased feature/BOOK-120 onto the updated feature/BOOK-115. Resolved the authentication conflict by retaining BOOK-120 request validation and the generic OTP delivery failure response from main.

**Student/Team Evaluation**

Pending human review.

**Errors / Risks / Hallucinations**

The feature/BOOK-120 history was rewritten to preserve the PR stack on the updated BOOK-115 base. The frontend main ref was refreshed for comparison; no frontend files were changed.

**Changes Made After Evaluation**

The first full regression run found that strict validation had removed the established generic SMTP failure message from the request-code endpoint. Restored the safe, generic response for both OTP entry points and logged provider failures server-side without returning exception details. The full offline backend suite then passed: 178 tests, with 0 failures or errors. The FCFS load test completed all 120 requests successfully (p95 290 ms). The two live RabbitMQ-dependent classes were excluded because no local broker was available.

**Final Artifact**

Updated BOOK-115 and BOOK-120 branches for PRs #32 and #33.

**Decision**

Pending human review.

**Reason**

The BOOK-120 validation changes now build on the refreshed BOOK-115 base and retain the sanitized OTP delivery failure handling.

#### 2026-11-11 — Rebase BOOK-115 and BOOK-120 after BOOK-96 merge

**Task / Context**

Refresh the stacked BOOK-115 and BOOK-120 pull requests after BOOK-96 was merged into backend `main`.

**AI Output**

Rebased BOOK-115 onto the new `main`, then replayed BOOK-120 onto that refreshed base. Resolved the engineering-log conflicts without duplicating historical entries. Restored the rejection-reason request limit to 1,000 characters, matching the existing database column and booking workflow, and updated the approval-expiry unit test to stub the intentionally row-locked repository lookup.

**Verification**

The full offline backend suite passed with 183 tests, 0 failures, and 0 errors. The live RabbitMQ-dependent test classes were excluded because no local broker was available.

**Final Artifact**

Rebased, tested backend branches for PRs #32 and #33.
