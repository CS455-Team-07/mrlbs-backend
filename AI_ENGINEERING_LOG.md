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

#### [2026-10-10] — Fixing admin dashboard data loading and backend port conflict

**Task / Context:**\
The admin dashboard was not rendering resource data reliably because the backend was still attempting to bind to port 8080 while a stale Java process held that port. The frontend also defaulted to the same URL, which prevented the dashboard from loading configured resources.

**Prompt:**\
Diagnose why the admin dashboard was blank and why the backend could not start; then make the app use a free port and ensure the dashboard loads configured resources.

**AI Output:**\
Identified the stale Java process on port 8080, confirmed the frontend fetch URL defaulted to localhost:8080, and verified the resource fetch flow and admin configure UI were present in code.

**Student/Team Evaluation:**\
The diagnosis was correct. The root cause was a port conflict plus a stale API URL default rather than a missing dashboard implementation.

**Errors / Risks / Hallucinations:**\
The initial backend startup attempts failed because the Maven wrapper was invoked from the wrong working directory and the stale port listener remained active. This was resolved by starting the service from the backend project folder and moving it to a free port.

**Changes Made After Evaluation:**\
Updated the backend default port to 8081, updated the frontend API base URL to 8081, and verified that the backend and admin dashboard logic run with the same endpoint.

**Final Accepted Artifact:**\
A backend running on port 8081 with the admin dashboard fetching resources from the correct endpoint and a matching frontend default API base URL.

**Decision:**\
Accepted

**Reason:**\
This resolves the startup blocker and ensures the resource list and Configure workflow can load correctly in the same development environment.
