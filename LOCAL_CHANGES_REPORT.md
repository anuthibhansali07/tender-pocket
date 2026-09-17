# TenderPocket — Repository Change Report (Base to Local)

This report provides a complete, line-by-line and architectural account of all changes made from the base repository commit (`4c913b6`) to the current local repository state. 

**Alongside every change, the specific problem, root cause, and justification ("WHY") are documented.**

> **Important Boundary Compliance**: All modifications and new files are kept 100% local in the working tree. Zero Git commits, adds, or pushes have been made.

---

## 1. Executive Summary

| Category | Count | Summary |
|---|---|---|
| **Modified Files** | 15 | Port configurations, authentication, role parsing, repository queries, UI forms, permission logic, schema migrations. |
| **New Untracked Files** | 15 | Dedicated Approvals Center component, backend REST controllers, DTOs, approval Next.js API routes, workflow action handlers, seeding scripts. |
| **Core Systems Impacted** | 4 | 1. MIS Approvals Center & Team Queues<br>2. Tender Executive Assignment & Quantity Management<br>3. End-to-End Multi-Stage Workflow Coordination<br>4. Multi-Database (PostgreSQL + SQLite) Dual-Run Architecture |

---

## 2. Directory-Level Overview of Changes

```
tender-pocket/
├── LOCAL_CHANGES_REPORT.md                          [NEW]  Complete changelog and technical audit report with "Why" rationale
├── README.md                                        [MOD]  Updated port documentation and credentials
├── tenders.db                                       [NEW]  Local SQLite database with workflow schema
│
├── backend/                                         (Spring Boot on port 8090)
│   ├── src/main/resources/application.properties   [MOD]  Changed default port to 8090; JWT environment overrides
│   └── src/main/java/com/tenderpocket/
│       ├── config/JwtRequestFilter.java             [MOD]  Added multi-word role parsing (MIS Team, Tender Executive)
│       ├── controllers/
│       │   ├── AnalyticsController.java             [MOD]  Null-safety in metrics calculation
│       │   ├── WorkflowController.java              [MOD]  Enhanced review logic, transitions, and status comments
│       │   └── ApprovalController.java              [NEW]  Dedicated Approvals Center REST controller (/api/approvals)
│       ├── models/
│       │   └── ApprovalSummaryDto.java              [NEW]  Enriched DTO combining approval requests with tender info
│       └── repositories/
│           └── TenderApprovalRepository.java        [MOD]  Added MIS Team badge count & pending list queries
│
└── frontend/                                        (Next.js on port 8085)
    ├── package-lock.json                            [MOD]  Cleaned conflicting dependency locks
    ├── scripts/
    │   ├── seed-workflow-tenders.js                 [NEW]  Comprehensive seeding script for all workflow stages
    │   ├── seed-test-approvals.js                   [NEW]  Standalone script to populate test approvals
    │   └── sync-pg-to-sqlite.js                     [NEW]  Data bridge between PostgreSQL and SQLite
    └── src/
        ├── lib/db.ts                                [MOD]  Added tender_approval_requests table, columns, user seeds
        └── app/
            ├── page.tsx                             [MOD]  Integrated Approvals Center tab, badge, drawer assignment
            ├── components/
            │   └── ApprovalsCenter.tsx              [NEW]  Full-featured Approvals Center UI with modal & filters
            ├── tenders/[id]/page.tsx                [MOD]  Unlocked assignment controls for Admin/MIS Team, role fixes
            └── api/
                ├── auth/
                │   ├── executives/route.ts          [MOD]  Fixed role filter to include 'Tender Executive'
                │   ├── login/route.ts               [MOD]  Added dual backend/SQLite auth and JWT signing
                │   └── users/route.ts               [MOD]  Added Tender Executive role stats and handling
                ├── approvals/
                │   ├── counts/route.ts              [NEW]  Proxy/fallback for badge & stage counts
                │   ├── pending/route.ts             [NEW]  Proxy/fallback for pending approval lists
                │   └── [id]/review/route.ts         [NEW]  Proxy/fallback for approval review submissions
                └── tenders/
                    ├── route.ts                     [MOD]  Expanded search, pagination, and multi-filter queries
                    ├── [id]/route.ts                [MOD]  Added PATCH handler for executive, quantities, and notes
                    ├── [id]/clearance-request/      [NEW]  Endpoint for executive spec clearance submission
                    ├── [id]/approve-clearance/      [NEW]  Endpoint for clearance team spec approval
                    ├── [id]/tpc-price/              [NEW]  Endpoint for TPC team purchase price submission
                    ├── [id]/mis-price/              [NEW]  Endpoint for MIS team price finalization
                    ├── sync-emails/                 [NEW]  IMAP email sync endpoint
                    └── sync-gem/                    [NEW]  GeM portal scraping/sync endpoint
```

---

## 3. Detailed File-by-File Changes and "WHY" Rationale

### 3.1 Backend Configuration & Security

#### [`backend/src/main/resources/application.properties`](file:///d:/tender-pocket/backend/src/main/resources/application.properties)
* **What Changed:**
  - Changed default server port from `8080` to `8090` (`server.port=${SPRING_PORT:8090}`).
  - Made JWT properties configurable via environment variables (`jwt.secret=${JWT_SECRET:...}` and `jwt.expiration=${JWT_EXPIRATION:315360000}`).
* **Why We Made This Change:**
  - **Port Collision Issue:** On Windows development machines, port `8080` was already occupied by Oracle TNS Listener / background services. Starting Spring Boot on `8080` resulted in `BindException: Address already in use`.
  - **Shared Secret Synchronization:** Hardcoding the JWT secret in Java caused mismatches whenever Next.js ran in different environments. Allowing environment variable overrides ensures both Next.js and Spring Boot verify tokens using identical credentials.

---

#### [`backend/src/main/java/com/tenderpocket/config/JwtRequestFilter.java`](file:///d:/tender-pocket/backend/src/main/java/com/tenderpocket/config/JwtRequestFilter.java)
* **What Changed:**
  - Standardized role extraction and conversion: replaces whitespace with underscores and prepends `ROLE_` (e.g. `"MIS Team"` &rarr; `ROLE_MIS_TEAM`, `"Tender Executive"` &rarr; `ROLE_TENDER_EXECUTIVE`, `"Clearance Team"` &rarr; `ROLE_CLEARANCE_TEAM`).
  - Added support for both `HS256` and `HS512` token header algorithms.
* **Why We Made This Change:**
  - **Authentication Rejection Bug:** In Spring Security, authority names with spaces (such as `"MIS Team"`) broke standard expression evaluations (`hasRole('MIS_TEAM')`). Spring Security either threw an `IllegalArgumentException` or treated the user as unauthenticated.
  - **Algorithm Mismatch:** Next.js generated HS256-signed JWTs by default, while Spring Boot expected HS512. Legitimate requests from the frontend were being rejected with 401/403 errors due to signature format incompatibilities.

---

### 3.2 Backend Controllers & Repositories

#### [`backend/src/main/java/com/tenderpocket/repositories/TenderApprovalRepository.java`](file:///d:/tender-pocket/backend/src/main/java/com/tenderpocket/repositories/TenderApprovalRepository.java)
* **What Changed:**
  - Added `countPendingByStageForAdmin()`: Single DB round-trip `GROUP BY stage` query.
  - Added `countPendingByStageForMisTeam()`: Aggregates pending approval counts for the entire MIS team queue.
  - Added `findPendingForMisTeam()` and `findPendingByStageForMisTeam()`: Retrieves requests matching the user, general queue (`'misteam'`, `'MIS Team'`), unassigned items, or MIS-governed workflow stages.
* **Why We Made This Change:**
  - **Zero Count Bug for MIS Team Members:** The base repository queried approvals with `WHERE a.assignedTo = :assignedTo`. When user `team_1` logged in, Spring Boot searched exclusively for records where `assigned_to = 'team_1'`. Because existing pending requests were assigned to `'misteam'` (or unassigned queue), `team_1` was shown `TOTAL: 0` in the Approvals Center.
  - **Department Workflow Justification:** In tender operations, approvals are handled by team queues. Any member of the MIS team (`team_1`, `misteam`) must be able to view, pick up, and action pending requests for EMD payments, doc verifications, submissions, and win/loss decisions.

---

#### [`backend/src/main/java/com/tenderpocket/controllers/ApprovalController.java`](file:///d:/tender-pocket/backend/src/main/java/com/tenderpocket/controllers/ApprovalController.java) [NEW]
* **What Changed:**
  - Created a dedicated Spring Boot REST controller mapped to `/api/approvals` with three primary endpoints:
    1. `GET /api/approvals/counts`: Returns total and stage-by-stage pending counts.
    2. `GET /api/approvals/pending`: Returns pending requests enriched with tender details.
    3. `POST /api/approvals/{id}/review`: Processes review decisions (`APPROVED`, `REJECTED`, `CHANGES_REQUESTED`), logs comments, and advances tender stages.
* **Why We Made This Change:**
  - **Missing Approvals Center Backend:** The base repository had no centralized controller for the Approvals Center. Fetching approval items previously required loading all tenders and iterating over nested approval lists on the client, which caused severe performance degradation and N+1 database round-trips.
  - **Clean Separation of Concerns:** Separating Approvals Center operations from `WorkflowController` ensures dedicated caching, query optimization, and distinct role authorization.

---

#### [`backend/src/main/java/com/tenderpocket/models/ApprovalSummaryDto.java`](file:///d:/tender-pocket/backend/src/main/java/com/tenderpocket/models/ApprovalSummaryDto.java) [NEW]
* **What Changed:**
  - Created a transfer object that merges `TenderApprovalRequest` data with parent `Tender` fields (tender title, reference number, authority, estimated cost, EMD amount, due date, status, assigned executive).
* **Why We Made This Change:**
  - **N+1 Query Elimination:** An approval record in the database only holds a foreign key `tenderId`. Displaying tender details in the Approvals Center table previously required the frontend to issue a separate `GET /api/tenders/{id}` request for every single row. `ApprovalSummaryDto` consolidates all required data into one single response.

---

#### [`backend/src/main/java/com/tenderpocket/controllers/WorkflowController.java`](file:///d:/tender-pocket/backend/src/main/java/com/tenderpocket/controllers/WorkflowController.java)
* **What Changed:**
  - Implemented automatic tender stage advancement upon approval review:
    - Approving `SPEC_CLEARANCE` &rarr; sets `tender.currentStage = 'TPC_PRICING'`
    - Approving `PAYMENT_APPROVAL` &rarr; sets `tender.currentStage = 'DOC_VERIFICATION'`
    - Approving `DOC_VERIFICATION` &rarr; sets `tender.currentStage = 'SUBMISSION_PENDING'`
    - Submitting `WIN_LOSS_PENDING` with loss &rarr; sets `tender.status = 'Lost'` and records `loss_reason_mis`
  - Added structured review remarks logging into `tender_comments`.
* **Why We Made This Change:**
  - **Workflow Stalling Bug:** Previously, approving a request only updated the status of the approval row to `'APPROVED'`, but left `tenders.currentStage` unchanged. Consequently, tenders were permanently stuck in intermediate stages (e.g. Technical Specs approved, but never transitioning to TPC pricing).

---

#### [`backend/src/main/java/com/tenderpocket/controllers/AnalyticsController.java`](file:///d:/tender-pocket/backend/src/main/java/com/tenderpocket/controllers/AnalyticsController.java)
* **What Changed:**
  - Added null-safety checks and fallback values (`0.0`, `"N/A"`) on `estimatedCost`, `dueDate`, and `status` streams.
* **Why We Made This Change:**
  - **Crash Prevention:** Scraping data from GeM portals frequently yields tenders with missing estimated costs or unparsed due dates. When the analytics controller ran arithmetic aggregations on null fields, it crashed with unhandled `NullPointerException`s.

---

### 3.3 Frontend UI & User Interaction

#### [`frontend/src/app/page.tsx`](file:///d:/tender-pocket/frontend/src/app/page.tsx)
* **What Changed:**
  1. **Approvals Center Integration:** Added an `'approvals'` view tab accessible to `MIS Team` and `Admin`.
  2. **Live Badge Counter:** Displayed real-time pending approvals count next to the top navigation button.
  3. **Drawer Executive Assignment:** Removed `disabled={currentUser.role === 'Admin'}` and `cursor: not-allowed` on executive `<select>`, `Bid Quantity`, and `Quoted Quantity`.
  4. **Direct Action Buttons:** Added a prominent **"Save Assignment"** button to the header of *Executive & Quantity Management* and a **"Save Notes"** button for internal notes.
* **Why We Made This Change:**
  - **Hidden / Inaccessible Approvals:** Users had no navigation tab to reach the Approvals Center, and no visual indication that approvals were awaiting their review.
  - **Admin Locked Out Bug:** Admins were completely blocked from assigning executives or adjusting quantities because form inputs were hardcoded to `disabled={currentUser.role === 'Admin'}`.
  - **Unsaved Changes Confusion:** Previously, saving executive assignments was tied only to the "Save Changes" button under "Internal Bidding Notes" at the bottom of the drawer. Users selecting an executive naturally expected their assignment to save immediately; closing the drawer resulted in lost assignments.

---

#### [`frontend/src/app/tenders/[id]/page.tsx`](file:///d:/tender-pocket/frontend/src/app/tenders/%5Bid%5D/page.tsx)
* **What Changed:**
  - Line 376: Updated filter from `u.role === 'MIS Executive'` to `u.role === 'MIS Executive' || u.role === 'Tender Executive' || u.role === 'Executive'`.
  - Removed `disabled={currentUser?.role === 'Admin'}` from the executive `<select>`, `Bid Quantity`, and `Quoted Quantity`.
  - Added direct **"Save Assignment"** button next to the assignment card header.
* **Why We Made This Change:**
  - **Empty Dropdown Bug on Tender Page:** The executive dropdown was rendering empty (`[]`) because the frontend filter looked for `MIS Executive`, while users were stored as `Tender Executive`.
  - **Admin Parity:** Allowed Admins to manage assignments and quantities directly on the full tender details page.

---

#### [`frontend/src/app/components/ApprovalsCenter.tsx`](file:///d:/tender-pocket/frontend/src/app/components/ApprovalsCenter.tsx) [NEW]
* **What Changed:**
  - Created a comprehensive, self-contained Approvals Center UI:
    - Stage filter pills: *All*, *Payment Approval*, *Document Verification*, *Submission Pending*, *Win/Loss*, *Technical Specs*, *TPC Pricing*, *MIS Pricing*.
    - Interactive count badges on stage buttons.
    - Search bar filtering by title, tender ID, ref number, or authority.
    - Data table showing tender metadata, stage specifics (EMD, NEFT ref, shared path, loss reasons), and action buttons.
    - Review Modal allowing reviewers to Approve, Reject, or Request Changes with required remarks and loss reasons.
* **Why We Made This Change:**
  - **Missing Core Business Functionality:** The application lacked a unified dashboard for MIS management to clear bidding stages. Without this component, verifying payments, inspecting bid documents, and authorizing submissions required manual database manipulation.

---

### 3.4 Frontend API Routes & Database Layer

#### [`frontend/src/app/api/auth/executives/route.ts`](file:///d:/tender-pocket/frontend/src/app/api/auth/executives/route.ts)
* **What Changed:**
  - Changed SQL query from `WHERE role = 'MIS Executive'` to `WHERE role = 'MIS Executive' OR role = 'Tender Executive' OR role = 'Executive'`.
* **Why We Made This Change:**
  - **Root Cause of Empty Executive Dropdown:** In the database seed, executive accounts (`executive`, `executive_1`) were created with `role = 'Tender Executive'`. Because this route strictly queried `WHERE role = 'MIS Executive'`, it returned `{ success: true, executives: [] }`.

---

#### [`frontend/src/app/api/auth/users/route.ts`](file:///d:/tender-pocket/frontend/src/app/api/auth/users/route.ts)
* **What Changed:**
  - Added `'Tender Executive'` to accepted roles in user creation and role-based metrics.
* **Why We Made This Change:**
  - **User Management Inconsistency:** Creating new executive users or calculating workload metrics failed because `'Tender Executive'` was rejected by role validation logic.

---

#### [`frontend/src/app/api/auth/login/route.ts`](file:///d:/tender-pocket/frontend/src/app/api/auth/login/route.ts)
* **What Changed:**
  - Added dual authentication: attempts Spring Boot authentication first, and falls back to SQLite `users` table.
  - Standardized JWT payload generation using `JWT_SECRET`.
* **Why We Made This Change:**
  - **Offline / Dual-Run Resilience:** If Spring Boot was temporarily down or restarting, users were locked out of the frontend. Implementing SQLite fallback with proper SHA-256 password verification ensures high availability.

---

#### [`frontend/src/app/api/tenders/[id]/route.ts`](file:///d:/tender-pocket/frontend/src/app/api/tenders/%5Bid%5D/route.ts)
* **What Changed:**
  - Implemented comprehensive `PATCH` handler:
    - Saves `mis_executive`, sets `assigned_by` to the current username, and timestamps `assigned_at`.
    - Auto-transitions tender status to `'Participating'` if currently `'New'`, `'Issued'`, or `'Lapsed'`.
    - Saves `bid_qty`, `quoted_qty`, and `notes`.
    - Writes audit logs to `activity_logs`.
* **Why We Made This Change:**
  - **Persistence Failure:** Previously, attempting to update executive assignments or quantities returned `405 Method Not Allowed` or ignored the assignment fields, leaving database values unchanged.

---

#### [`frontend/src/lib/db.ts`](file:///d:/tender-pocket/frontend/src/lib/db.ts)
* **What Changed:**
  - Created table `tender_approval_requests` mirroring Spring Boot's JPA entity.
  - Added dynamic migrations for missing columns: `current_stage`, `tpc_purchase_price`, `mis_final_price`, `assigned_mis_member_spec`, `spec_verification_status`.
  - Seeded default accounts with hashed passwords: `admin`, `misteam`, `team_1`, `executive`, `executive_1`, `clearance`, `tpc`.
* **Why We Made This Change:**
  - **Schema Desynchronization:** SQLite lacked the tables and columns required by the multi-stage workflow. Queries against SQLite threw `SqliteError: no such table: tender_approval_requests`.
  - **Testing Reliability:** Pre-seeding standard accounts enables immediate verification without needing manual user registration.

---

#### [`frontend/src/app/api/approvals/counts/route.ts`](file:///d:/tender-pocket/frontend/src/app/api/approvals/counts/route.ts) [NEW]
* **What Changed:**
  - Created route that forwards requests to Spring Boot `:8090/api/approvals/counts`.
  - Added fallback to SQLite that aggregates pending counts for `MIS Team` queue and `Admin`.
* **Why We Made This Change:**
  - **Badge Count Resilience:** Powers the top navigation notification badge and stage pill counters, guaranteeing accurate counts even if the Spring Boot service is temporarily unreachable.

---

#### [`frontend/src/app/api/approvals/pending/route.ts`](file:///d:/tender-pocket/frontend/src/app/api/approvals/pending/route.ts) [NEW]
* **What Changed:**
  - Created route that forwards requests to Spring Boot `:8090/api/approvals/pending`.
  - Added SQLite fallback with joined tender fields, stage filtering, and search.
* **Why We Made This Change:**
  - **Pending List Delivery:** Supplies the Approvals Center data table with full tender context and stage-specific parameters.

---

#### [`frontend/src/app/api/approvals/[id]/review/route.ts`](file:///d:/tender-pocket/frontend/src/app/api/approvals/%5Bid%5D/review/route.ts) [NEW]
* **What Changed:**
  - Created route that forwards review actions (`APPROVED`, `REJECTED`, `CHANGES_REQUESTED`) to Spring Boot or executes stage transitions in SQLite.
* **Why We Made This Change:**
  - **Action Execution:** Enables reviewers to submit decisions directly from the Next.js UI.

---

### 3.5 Workflow Action Endpoints & Scripts

#### Workflow Endpoints [NEW]:
* [`/api/tenders/[id]/clearance-request`](file:///d:/tender-pocket/frontend/src/app/api/tenders/%5Bid%5D/clearance-request/route.ts): Submitted by Executive to request technical specification approval.
* [`/api/tenders/[id]/approve-clearance`](file:///d:/tender-pocket/frontend/src/app/api/tenders/%5Bid%5D/approve-clearance/route.ts): Actioned by Clearance Team to approve specifications and advance tender to TPC pricing.
* [`/api/tenders/[id]/tpc-price`](file:///d:/tender-pocket/frontend/src/app/api/tenders/%5Bid%5D/tpc-price/route.ts): Actioned by TPC Team to set manufacturer purchase price.
* [`/api/tenders/[id]/mis-price`](file:///d:/tender-pocket/frontend/src/app/api/tenders/%5Bid%5D/mis-price/route.ts): Actioned by MIS Team to set final bidding price.
* **Why We Made These Changes:**
  - Each endpoint represents a required gate in the Government e-Marketplace (GeM) bidding lifecycle, ensuring proper separation of duties between Technical, Pricing, and MIS teams.

---

#### Testing & Seeding Scripts [NEW]:
* **[`frontend/scripts/seed-workflow-tenders.js`](file:///d:/tender-pocket/frontend/scripts/seed-workflow-tenders.js)**:
  - **Why We Made This Change:** Manual testing of multi-stage workflows required hours of repetitive UI clicking across multiple browser tabs and logins. This script sets up deterministic test cases for every stage in both SQLite and PostgreSQL in seconds.
* **[`frontend/scripts/sync-pg-to-sqlite.js`](file:///d:/tender-pocket/frontend/scripts/sync-pg-to-sqlite.js)**:
  - **Why We Made This Change:** Kept SQLite in sync with PostgreSQL so developers can switch modes seamlessly without data drift.

---

## 4. Verification & Testing Evidence

| Verification Step | Command / Query | Outcome | Why It Matters |
|---|---|---|---|
| **Executive Dropdown** | `GET /api/auth/executives` | `['executive', 'executive_1']` | Confirms executives are loaded and selectable in UI. |
| **`team_1` Approvals Count** | `GET :8085/api/approvals/counts` | `TOTAL: 9` | Proves `team_1` has full visibility into the MIS department queue. |
| **`team_1` Pending List** | `GET :8085/api/approvals/pending` | `9` items returned | Confirms table is populated with enriched tender data. |
| **Assignment Persistence** | `PATCH /api/tenders/9816988` | Persisted: `executive`, `team_1`, `bid_qty: 50` | Proves one-click assignment saves to database. |
| **Stage Auto-Advance** | `POST :8090/api/tenders/9849390/approve-clearance` | `currentStage = TPC_PRICING` | Proves approving clearance moves tender to pricing. |
