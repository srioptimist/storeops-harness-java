# Sprint 1 Contract — Bulk status endpoint with per-item results, audit write and events

| Field | Value |
|---|---|
| Run-id | 2026-10-05-shift-handover-bulk-update |
| Spec reference | spec.md §6, sprint 1 (revision 0) |
| Depends on | none |
| Allowed paths | src/activities/src/main/java/com/storeops/activities/**, src/activities/src/test/java/** |
| Gate command | ./mvnw -B test (exit 0) |

## Goal
`PATCH /api/activities/bulk-status` sets each listed own-store activity to DONE or BLOCKED, returns a per-item result list with HTTP 200, saves one `ActivityAuditEntry` and emits one `ActivityStatusChangedEvent` per `UPDATED` task.

## Acceptance criteria

### AC-1.1 — bulk update returns 200 with per-item results  [verify: routes-test]
- GIVEN an authenticated DEPARTMENT_LEAD of store-001 and a mocked service returning a result with `batchId = "b-1"`, `requested = 2`, `updated = 1`, `unchanged = 0`, `failed = 1`, `results[0] = {activityId: "a1", outcome: UPDATED, previousStatus: TODO, status: DONE, auditId: "au-1"}`, `results[1] = {activityId: "zz", outcome: FAILED, reason: "NOT_FOUND"}`
- WHEN they PATCH /api/activities/bulk-status with `{"items":[{"activityId":"a1","status":"DONE"},{"activityId":"zz","status":"BLOCKED"}],"note":"end of late shift"}`
- THEN the response is 200 with `$.batchId = "b-1"`, `$.requested = 2`, `$.updated = 1`, `$.failed = 1`, `$.results[0].outcome = "UPDATED"`, `$.results[0].status = "DONE"`, `$.results[1].outcome = "FAILED"`, `$.results[1].reason = "NOT_FOUND"`
- AND the service's bulk method is invoked exactly once with the caller and a command holding 2 items in request order and `note = "end of late shift"`

### AC-1.2 — bulk update rejects more than 50 items  [verify: routes-test]
- GIVEN an authenticated DEPARTMENT_LEAD of store-001
- WHEN they PATCH /api/activities/bulk-status with 51 items, each `{"activityId":"a<n>","status":"DONE"}`
- THEN the response is 400 with `$.code = "VALIDATION_ERROR"` and `$.statusCode = 400`
- AND the service's bulk method is never invoked

### AC-1.3 — bulk update rejects a target status other than DONE or BLOCKED  [verify: service-test]
- GIVEN store-001 activity `a1` with status TODO
- WHEN `bulkUpdateStatus` is called by a store-001 caller with items `[{a1, IN_PROGRESS}]`
- THEN a `ValidationError` (code `VALIDATION_ERROR`) is thrown
- AND `a1` still has status TODO, the audit repository holds 0 entries for `a1`, and `EventBus.emit` is never called

### AC-1.4 — bulk update rejects duplicate activity ids  [verify: service-test]
- GIVEN store-001 activity `a1` with status TODO
- WHEN `bulkUpdateStatus` is called by a store-001 caller with items `[{a1, DONE}, {a1, BLOCKED}]`
- THEN a `ValidationError` (code `VALIDATION_ERROR`) is thrown
- AND `a1` still has status TODO, the audit repository holds 0 entries for `a1`, and `EventBus.emit` is never called

### AC-1.5 — partial failure: unknown and other-store items fail, own-store item is updated  [verify: service-test]
- GIVEN store-001 activity `a1` (status TODO) and store-002 activity `b1` (status TODO), and no activity with id `missing`
- WHEN `bulkUpdateStatus` is called by caller `staff-lead-001` of store-001 with items `[{a1, DONE}, {missing, DONE}, {b1, BLOCKED}]`
- THEN the result has `requested = 3`, `updated = 1`, `unchanged = 0`, `failed = 2`, and `results` in request order with outcomes `UPDATED`, `FAILED`, `FAILED`; `results[1].reason = "NOT_FOUND"` and `results[2].reason = "NOT_FOUND"`
- AND the repository returns `a1` with status DONE and `b1` with status TODO

### AC-1.6 — item already in the target status is UNCHANGED  [verify: service-test]
- GIVEN store-001 activity `a2` with status BLOCKED and `updatedAt = T0`
- WHEN `bulkUpdateStatus` is called by a store-001 caller with items `[{a2, BLOCKED}]`
- THEN the result has `updated = 0`, `unchanged = 1`, `results[0].outcome = UNCHANGED`, `results[0].auditId = null`
- AND `a2` still has `updatedAt = T0`, and the audit repository holds 0 entries for `a2`

### AC-1.7 — one audit entry is stored per updated task  [verify: service-test]
- GIVEN store-001 activities `a1` (status TODO) and `a3` (status IN_PROGRESS)
- WHEN `bulkUpdateStatus` is called by caller `staff-lead-001` of store-001 with items `[{a1, DONE}, {a3, BLOCKED}]` and `note = "handover"`
- THEN the audit repository holds exactly 1 entry for `a1` with `previousStatus = TODO`, `newStatus = DONE`, `actorId = "staff-lead-001"`, `storeId = "store-001"`, `note = "handover"`, and exactly 1 entry for `a3` with `previousStatus = IN_PROGRESS`, `newStatus = BLOCKED`
- AND both entries share `batchId` equal to the result's `batchId`, and each `results[i].auditId` equals the stored entry's `id`

### AC-1.8 — one ActivityStatusChangedEvent per updated task, none for unchanged or failed  [verify: event-test]
- GIVEN store-001 activity `a1` (status TODO, ownerId `staff-manager-001`), store-001 activity `a2` (status BLOCKED), and no activity `missing`
- WHEN `bulkUpdateStatus` is called by a store-001 caller with items `[{a1, BLOCKED}, {a2, BLOCKED}, {missing, DONE}]`
- THEN `EventBus.emit` is called exactly once, with an `ActivityStatusChangedEvent` having `activityId = "a1"`, `previousStatus = "TODO"`, `newStatus = "BLOCKED"`, `ownerId = "staff-manager-001"`, `storeId = "store-001"`, and non-null `occurredAt`
- AND at the time of the emit call the task repository already returns `a1` with status BLOCKED and the audit repository already holds the `a1` entry

## Out of scope for this sprint
- `GET /api/activities/{id}/audit` and any other audit read route (sprint 2).
- Integration tests in `src/app` and any alert assertion through the real bus (sprint 2).
- Audit writes from `PATCH /api/activities/{id}`, `POST` or `DELETE`.
- Changes to `shared`, `alerts`, `staff`, `programmes`, `reports`, or any `pom.xml`.

## Definition of done
- All ACs MET in the generator summary, each with a test whose @DisplayName starts with its AC id.
- Gate command exits 0 with no protected-path changes and no suppressions.
- Existing `ActivityControllerTest` and `ActivityServiceTest` cases still pass unmodified in behaviour (`PATCH /api/activities/{id}` unchanged).
