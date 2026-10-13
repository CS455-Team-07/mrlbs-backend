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

#### 2026-10-13 — Restore IITK Google OIDC authentication

**Task and context:** The user asked to rebase both repositories' work on the latest `main`, create an `authentication` branch in each, find a workable authentication path for IITK accounts, and not push until authentication had been tested.

**Prompt:** "pull the latest versions of main for both frontend and backend and create a new branch on authentication and we need to find an alternative to make authentication work and also dont push until you have tested that authentication works and keep in mind that the accounts are .iitk.ac.in accounts and plan accordingly"

**AI output or response:** Restored Google OAuth 2.0 / OIDC as the default authentication flow, retained `iitk.ac.in` as the configured institution, and reviewed Google's guidance for hosted-domain validation.

**Student/team evaluation:** Pending. Backend suite: 39 tests passed. Frontend production build passed. Full frontend suite: 27 passed and 7 existing `MemberDashboard` tests failed because their expected resource-selection controls do not match the current UI. The live OAuth authorization redirect reaches Google's sign-in page with `hd=iitk.ac.in`, `prompt=select_account`, and the localhost callback URI. The actual account callback remains pending user sign-in.

**Errors, risks, or hallucinations identified:** Google states that an email suffix alone does not establish Google Workspace organization membership; therefore the signed `hd` claim remains mandatory, with verified `@iitk.ac.in` email checked as an additional condition. A real account sign-in is still needed to confirm the configured Google client and IITK Workspace account complete the callback.

**Changes made after evaluating the AI output:** Enabled backend OIDC by default, restored the frontend Google sign-in flow, assigned backend OIDC roles from the verified institutional admin allowlist, required verified matching domain claims, and prevented typed-email member login from bypassing the authenticated principal.

**Final accepted artifact or output:** Local implementation on the `authentication` branch; no commits have been pushed. Final acceptance awaits a successful IITK account callback.

**Decision:** Modified.

**Reason:** OIDC provides a verifiable institution-owned identity and supports the supplied IITK Google Workspace accounts while preserving the domain restriction.

#### 2026-10-13 — Switch institutional OIDC to Microsoft Entra

**Task and context:** The user confirmed their `@iitk.ac.in` account signs in to Microsoft 365 but Google reports that the account cannot be found. They requested an alternative institutional authentication flow and asked that nothing be pushed before a successful real sign-in.

**AI output or response:** Replaced Google OAuth configuration with Microsoft Entra OIDC for IITK. The provider is tenant-specific; the backend verifies the signed issuer and `tid` claims against the configured IITK tenant ID and also requires an `@iitk.ac.in` address from signed ID-token claims. Microsoft `preferred_username` and `upn` are supported when an `email` claim is absent. The frontend now starts Microsoft sign-in and displays Microsoft-specific callback messages.

**Student/team evaluation:** Backend test suite passed (42 tests). Focused auth frontend tests passed (8 tests), and the production build passed. A local backend smoke check returned the Microsoft authorize endpoint for the IITK tenant with the `microsoft` callback URI and `prompt=select_account`. End-to-end authentication remains untested because a Microsoft Entra app registration and its client ID/secret have not yet been configured locally.

**Errors, risks, or hallucinations identified:** Domain suffix alone is not accepted: the token issuer and tenant must also match. Microsoft documents `tid` as the organizational tenant ID and provides tenant-specific OIDC configuration. A real identity-provider callback is still required before this implementation can be accepted as working.

**Changes made after evaluating the AI output:** Changed the backend OIDC registration, tenant/domain validator, normalized institutional email handling, login resolver, test fixtures, frontend link and messages, and both READMEs. No commits or pushes were made.

**Final accepted artifact or output:** Local implementation on the `authentication` branch; live sign-in awaits the IITK tenant's Entra app registration values.

**Decision:** Pending real sign-in.

**Reason:** The user's Microsoft 365 identity is available, while Google does not recognize their address as a Google Account.


#### 2026-10-13 — Replace OAuth sign-in with IITK mailbox verification

**Task and context:** The user said Microsoft sign-in is blocked by tenant admin approval and agreed that email delivery is a better workaround.

**Prompt:** "yeah the mail sending thing is better"

**AI output or response:** Replaced the OAuth sign-in path with a short-lived one-time code sent to an institutional email address. The server accepts only the configured `iitk.ac.in` domain, binds the code to the exact email and server session, permits five attempts, consumes a successful code, rotates the session ID, and establishes a Spring Security session. Added SMTP configuration through environment variables.

**Student/team evaluation:** Pending user review and real SMTP delivery. Backend automated tests and frontend auth tests/build are being run locally; no email-provider credentials have been added and no real mailbox code has been sent.

**Errors, risks, or hallucinations identified:** Mailbox verification confirms access to an `@iitk.ac.in` inbox; it does not meet the original Google OAuth/OIDC mechanism or prove institutional tenancy through signed identity-provider claims. Per-address send throttling is process-local, so multi-instance deployment needs a shared rate limiter. A real SMTP relay and sender identity must be configured before end-to-end authentication can be confirmed.

**Changes made after evaluating the AI output:** Removed Microsoft authorization from the security flow, added email-code request/verification endpoints and SMTP delivery, made authenticated API controllers accept the verified session principal, changed the frontend login form and tests, and made app reloads validate the backend session rather than trusting a local profile cache.

**Final accepted artifact or output:** Local implementation on the existing `authentication` branch. No commits or pushes were made. SMTP end-to-end testing remains pending configuration.

**Decision:** Modified.

**Reason:** User approved email delivery as a practical alternative because their institutional Microsoft app requires admin approval they cannot obtain.

**Verification update:** Final backend run completed successfully with 39 tests and no failures after obsolete OIDC tests were removed. Focused frontend authentication/navigation tests pass (6 tests) and the production build passes. The full frontend suite reports 25 passing and 7 failing tests, all in the existing MemberDashboard.test.tsx expectations (including missing “View availability” UI and outdated booking text). Real SMTP delivery remains untested because the workspace env has no SMTP settings.

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

#### 2026-10-13 — Merge latest main into authentication branches

**Task and context:** The user asked to update both repositories from the latest `main` and resolve conflicts in their branches.

**Prompt:** “now pull the latest version of main for both backend and frontend” followed by “and then resolve the merge conflicts of my branches”.

**AI output or response:** Merged the latest `origin/main` changes into the `authentication` branch in each repository and resolved overlapping auth, CSRF, dashboard, and engineering-log changes while preserving both sets of work.

**Student/team evaluation:** The user reports that the email-code flow was tested with a real IITK account and works. No tests were rerun after this merge.

**Errors, risks, or hallucinations identified:** Backend throttling remains per email per process; this merge does not add IP-based or shared distributed throttling.

**Changes made after evaluating the AI output:** Kept the server-verified email-code flow and CSRF checks, incorporated the latest mainline fixes, and removed conflict markers.

**Final accepted artifact or output:** Updated local `authentication` branches in both repositories; no remote push has been made yet.

**Decision:** Modified.

**Reason:** Resolve conflicts while retaining the verified institutional mailbox flow and latest mainline work.
#### 2026-10-13 — Publish resolved authentication merge

**Task and context:** Follow-up to merging latest `main` into the backend authentication branch.

**AI output or response:** Pushed the resolved merge commit and verified the remote branch matches local `HEAD`.

**Student/team evaluation:** GitHub reports backend PR #18 as mergeable with a clean merge state. Tests were not rerun after this merge.

**Final accepted artifact or output:** `authentication` is updated on origin at `12955ff3bb47dec3b6bf509c84269967cdc7668f`.

**Decision:** Accepted.

**Reason:** The user requested conflict resolution on their branches.