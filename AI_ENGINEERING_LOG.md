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
