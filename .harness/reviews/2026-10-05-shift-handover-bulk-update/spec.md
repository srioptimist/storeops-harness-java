# Spec — Shift handover bulk status update

| Field | Value |
|---|---|
| Run-id | 2026-10-05-shift-handover-bulk-update |
| Feature prompt | "Add shift handover bulk update — PATCH /api/activities/bulk-status allowing outgoing shift staff to mark multiple operational activities as DONE or BLOCKED in a single request, with partial failure handling and an audit entry per updated task." |
| Source | developer message |
| Revision | 0 |

## 1. Intent
At the end of a shift, outgoing store staff (any `StaffRole`: ASSOCIATE, DEPARTMENT_LEAD,
STORE_MANAGER) must close out or flag many activities at once instead of issuing one
`PATCH /api/activities/{id}` per task. This feature adds a single bulk request that sets each listed
activity to DONE or BLOCKED, reports a per-item outcome so one bad id does not fail the whole
handover, and records an activities-owned audit entry for every task it changes. BLOCKED items keep
raising the existing ESCALATION alert to the activity owner through the event bus.

## 2. Scope
- In:
  - `PATCH /api/activities/bulk-status` (auth required), max 50 items, target status DONE or BLOCKED only.
  - Per-item outcome `UPDATED` / `UNCHANGED` / `FAILED` (with `reason`), HTTP 200 for any valid request (AP-5 "partial success is not an error").
  - Store scoping per item: a missing activity or another store's activity is item-level `FAILED` / `NOT_FOUND`.
  - One `ActivityAuditEntry` per `UPDATED` item, stored in a new activities-owned audit repository.
  - One `ActivityStatusChangedEvent` per `UPDATED` item (existing event, reused; no new event).
  - `GET /api/activities/{id}/audit` (auth required, store-scoped) to read audit entries back.
  - End-to-end integration test: BLOCKED via bulk update produces `ESCALATION` alert for the owner through the real bus.
- Out:
  - Writing audit entries from the existing single `PATCH /api/activities/{id}`, `POST` or `DELETE` (unchanged behaviour).
  - Bulk changes to priority, category or assignee; target statuses TODO or IN_PROGRESS.
  - Transactional all-or-nothing semantics / rollback of already updated items.
  - Role restrictions beyond the existing single-PATCH rules (no role check today — kept at parity).
  - Any change in `alerts`, `reports`, `staff`, `programmes`, or `shared` (no new event, no new `AppError`).
  - Persistence beyond the in-memory `ConcurrentHashMap` repositories; audit retention/paging.

## 3. Impact map
| Module | Layer | File (existing or NEW) | Change |
|---|---|---|---|
| activities | routes | `src/activities/src/main/java/com/storeops/activities/routes/ActivityController.java` (existing) | Add `@PatchMapping("/api/activities/bulk-status")` (sprint 1) and `@GetMapping("/api/activities/{id}/audit")` (sprint 2); each calls one service method |
| activities | routes | `.../activities/routes/BulkStatusUpdateRequest.java` (NEW) | Request record: `@NotEmpty @Size(max = 50) @Valid List<Item> items`, `@Size(max = 500) String note`; nested `Item(@NotBlank activityId, @NotNull TaskStatus status)`; `toCommand()` |
| activities | service | `.../activities/service/BulkStatusUpdate.java` (NEW) | Command record (items + note); collections copied with `List.copyOf` |
| activities | service | `.../activities/service/BulkStatusResult.java` (NEW, or nested records — Generator's choice) | Result record: `batchId`, `requested`, `updated`, `unchanged`, `failed`, `results` (list of per-item `BulkItemResult`) |
| activities | service | `.../activities/service/ActivityService.java` (existing) | New `bulkUpdateStatus(AuthenticatedUser, BulkStatusUpdate)` (sprint 1) and `auditTrail(AuthenticatedUser, String activityId)` (sprint 2). Reuses `findInCallerStore` scoping logic; emits `ActivityStatusChangedEvent` after save + audit write. The Generator may place the logic in a NEW `activities.service.ShiftHandoverService` instead, provided routes still call one service method |
| activities | model | `.../activities/model/ActivityAuditEntry.java` (NEW) | Immutable record `(id, activityId, storeId, actorId, previousStatus: TaskStatus, newStatus: TaskStatus, note, batchId, occurredAt: Instant)` |
| activities | model | `.../activities/model/BulkItemOutcome.java` (NEW) | Enum `{UPDATED, UNCHANGED, FAILED}` |
| activities | repository | `.../activities/repository/ActivityAuditRepository.java` (NEW) | `save(ActivityAuditEntry)`, `findByActivityId(String)` (ordered by `occurredAt` ascending) |
| activities | repository | `.../activities/repository/InMemoryActivityAuditRepository.java` (NEW) | `@Repository`, `ConcurrentHashMap`-backed |
| activities | test | `src/activities/src/test/java/com/storeops/activities/**` | Routes, service and event tests for sprint 1 and 2 |
| app | test | `src/app/src/test/java/com/storeops/ShiftHandoverBulkUpdateIntegrationTest.java` (NEW) | Sprint 2 end-to-end chain with seeded tokens and real `SpringEventBus` |

No change to `ActivityDirectory` (no new cross-module read), no pom changes, no new Maven module.

## 4. API contract

### PATCH /api/activities/bulk-status  (auth: required)
- Request:
  ```json
  {
    "items": [ { "activityId": "string", "status": "DONE | BLOCKED" } ],
    "note": "string (optional)"
  }
  ```
  Validation:
  - `items`: required, 1..50 entries (`@NotEmpty`, `@Size(max = 50)`) → else 400 `VALIDATION_ERROR`.
  - `items[].activityId`: `@NotBlank` → else 400 `VALIDATION_ERROR`.
  - `items[].status`: `@NotNull`; must be `DONE` or `BLOCKED`. `TODO` / `IN_PROGRESS` → 400 `VALIDATION_ERROR` (service `ValidationError`). A value that is not a `TaskStatus` name → 400 `VALIDATION_ERROR` (existing `HttpMessageNotReadableException` mapping).
  - Duplicate `activityId` values within `items` → 400 `VALIDATION_ERROR` (service `ValidationError`).
  - `note`: optional, max 500 chars (`@Size(max = 500)`) → else 400 `VALIDATION_ERROR`.
  - Any request-level 400 changes no activity, writes no audit entry and emits no event.
- Processing: items are processed in request order, independently. Per item:
  - activity missing, or `task.storeId != caller.storeId` → `outcome = "FAILED"`, `reason = "NOT_FOUND"`; nothing stored.
  - `task.status == items[i].status` → `outcome = "UNCHANGED"`; task not saved, `updatedAt` unchanged, no audit entry, no event.
  - otherwise → task saved with new status and `updatedAt = now`; one `ActivityAuditEntry` saved; then one `ActivityStatusChangedEvent` emitted; `outcome = "UPDATED"`.
  - A failed item never reverts an item already `UPDATED`.
- Response 200:
  ```json
  {
    "batchId": "uuid",
    "requested": 3, "updated": 1, "unchanged": 1, "failed": 1,
    "results": [
      { "activityId": "a1", "outcome": "UPDATED",   "previousStatus": "TODO", "status": "DONE", "reason": null,        "auditId": "uuid" },
      { "activityId": "a2", "outcome": "UNCHANGED", "previousStatus": "DONE", "status": "DONE", "reason": null,        "auditId": null },
      { "activityId": "zz", "outcome": "FAILED",    "previousStatus": null,   "status": null,   "reason": "NOT_FOUND", "auditId": null }
    ]
  }
  ```
  `results` has exactly one entry per request item, in request order. `requested = updated + unchanged + failed`. 200 is returned even when every item is `FAILED`.
- Errors:
  - `UNAUTHORIZED` 401 — no or invalid bearer token (resolver).
  - `VALIDATION_ERROR` 400 — any request-level validation rule above.

### GET /api/activities/{id}/audit  (auth: required)
- Request: path `id` only.
- Response 200: JSON array of `ActivityAuditEntry`, oldest first:
  `[{ "id", "activityId", "storeId", "actorId", "previousStatus", "newStatus", "note", "batchId", "occurredAt" }]`.
  An existing own-store activity with no entries returns `[]`.
- Errors:
  - `UNAUTHORIZED` 401 — no or invalid bearer token.
  - `NOT_FOUND` 404 — activity missing or belongs to another store (store scoping hides existence, AP-5).

## 5. Events and cross-module reads
| Event / api method | Publisher / provider | Consumer | Payload fields |
|---|---|---|---|
| `ActivityStatusChangedEvent` (existing, `com.storeops.shared.events`) | `ActivityService.bulkUpdateStatus` (activities), once per `UPDATED` item, after the task save and audit save | `alerts.events.AlertEventListener.onActivityStatusChanged` (existing, unchanged) → `ESCALATION` alert to `ownerId` when `newStatus = "BLOCKED"` | `activityId`, `title`, `previousStatus` (enum `name()`), `newStatus` (enum `name()`), `ownerId`, `storeId`, `occurredAt` |
| `StaffDirectory` / `ProgrammeDirectory` | — | — | No new read methods required |

The audit entry is activities-owned state written to the activities repository; it is not a
cross-module side effect, so it needs no event.

## 6. Sprints
| Sprint | Title | Goal | ACs | Depends on |
|---|---|---|---|---|
| 1 | Bulk status endpoint with per-item results, audit write and events | `PATCH /api/activities/bulk-status` updates own-store activities to DONE/BLOCKED with per-item outcomes, writes one audit entry and emits one `ActivityStatusChangedEvent` per updated task | AC-1.1 – AC-1.8 (8) | none |
| 2 | Audit read endpoint and end-to-end handover chain | `GET /api/activities/{id}/audit` exposes audit entries store-scoped, and an app integration test proves bulk BLOCKED → ESCALATION alert through the real bus | AC-2.1 – AC-2.5 (5) | sprint 1 |

## 7. Assumptions and open questions
- A-1: Bulk limit is 50 items per request; 51 or more → 400 `VALIDATION_ERROR`.
- A-2: Each item carries its own target `status` (mixed DONE and BLOCKED in one request is allowed); there is no request-level status.
- A-3: Authorisation is at parity with `PATCH /api/activities/{id}`: any authenticated staff member of the store (ASSOCIATE, DEPARTMENT_LEAD, STORE_MANAGER) may update any activity of their own store. No item-level `FORBIDDEN` reason exists.
- A-4: Any transition into DONE or BLOCKED is allowed (including DONE → BLOCKED and BLOCKED → DONE), matching the single PATCH, which enforces no transition rules. No `CONFLICT` reason exists.
- A-5: Item-level `reason` values are `AppError` code strings; the only one used in this feature is `"NOT_FOUND"`.
- A-6: Spring MVC resolves the literal path `/api/activities/bulk-status` ahead of the `/api/activities/{id}` template for PATCH, so `PATCH /api/activities/{id}` behaviour is unchanged.
- A-7: `batchId` is a random UUID generated per request and stored on every audit entry of that request; `actorId` is `caller.userId()`; `note` is copied to each audit entry (may be `null`).
- A-8: Audit entries are only written by the bulk endpoint in this feature; the single PATCH does not write them (out of scope).
- A-9: No locking across items is required; each item's read-modify-save uses the existing `TaskRepository.save` semantics.
- Q-1: none blocking. (Non-blocking confirmation requested: A-3 role parity and A-4 transition freedom — say REVISE if ASSOCIATEs should be limited to tasks they own or are assigned, or if DONE → BLOCKED should be rejected as `CONFLICT`.)

STATUS: APPROVED 2026-10-05T16:56:03Z
