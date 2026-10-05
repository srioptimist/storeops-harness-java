# Sprint 2 Contract — Audit read endpoint and end-to-end handover chain

| Field | Value |
|---|---|
| Run-id | 2026-10-05-shift-handover-bulk-update |
| Spec reference | spec.md §6, sprint 2 (revision 0) |
| Depends on | sprint 1 |
| Allowed paths | src/activities/src/main/java/com/storeops/activities/**, src/activities/src/test/java/**, src/app/src/test/java/com/storeops/ShiftHandoverBulkUpdateIntegrationTest.java |
| Gate command | ./mvnw -B test (exit 0) |

## Goal
Expose stored audit entries through store-scoped `GET /api/activities/{id}/audit`, and prove with seeded tokens and the real event bus that a bulk BLOCKED update raises an `ESCALATION` alert for the activity owner.

## Acceptance criteria

### AC-2.1 — audit trail returns entries oldest first  [verify: routes-test]
- GIVEN an authenticated DEPARTMENT_LEAD of store-001 and a mocked service returning 2 entries for activity `a1`: first `{newStatus: BLOCKED, batchId: "b-1"}`, second `{newStatus: DONE, batchId: "b-2"}`
- WHEN they GET /api/activities/a1/audit
- THEN the response is 200 with a JSON array of length 2, `$[0].newStatus = "BLOCKED"`, `$[0].batchId = "b-1"`, `$[1].newStatus = "DONE"`, and each element has fields `id`, `activityId`, `storeId`, `actorId`, `previousStatus`, `newStatus`, `note`, `batchId`, `occurredAt`

### AC-2.2 — audit trail of another store's activity is NOT_FOUND  [verify: service-test]
- GIVEN store-002 activity `b1` with 1 stored audit entry
- WHEN `auditTrail` is called by a store-001 caller for `b1`
- THEN a `NotFoundError` (code `NOT_FOUND`, status 404) is thrown

### AC-2.3 — bulk update without a token is rejected  [verify: integration-test]
- GIVEN the running app with seeded data and an existing store-001 activity created by `dev-token-manager` with status TODO
- WHEN a client PATCHes /api/activities/bulk-status with `{"items":[{"activityId":"<that id>","status":"DONE"}]}` and no `Authorization` header
- THEN the response is 401 with `$.code = "UNAUTHORIZED"`
- AND GET /api/activities/<that id> returns `status = "TODO"`

### AC-2.4 — bulk BLOCKED raises ESCALATION for the owner through the real bus  [verify: integration-test]
- GIVEN store-001 activities `X` and `Y` created by `dev-token-manager` (ownerId `staff-manager-001`), both status TODO
- WHEN `dev-token-lead` PATCHes /api/activities/bulk-status with `{"items":[{"activityId":"X","status":"BLOCKED"},{"activityId":"Y","status":"DONE"}]}`
- THEN the response is 200 with `$.updated = 2` and `$.failed = 0`
- AND GET /api/alerts with `dev-token-manager` contains exactly one element with `type = "ESCALATION"` and `referenceId = X`, and no element with `type = "ESCALATION"` and `referenceId = Y`

### AC-2.5 — end-to-end partial failure leaves other-store task untouched and audits the updated task  [verify: integration-test]
- GIVEN store-001 activity `X` created by `dev-token-manager` (status TODO) and store-002 activity `Z` created by `dev-token-other-store` (status TODO)
- WHEN `dev-token-lead` PATCHes /api/activities/bulk-status with `{"items":[{"activityId":"X","status":"DONE"},{"activityId":"Z","status":"DONE"}],"note":"night handover"}`
- THEN the response is 200 with `$.results[0].outcome = "UPDATED"`, `$.results[1].outcome = "FAILED"`, `$.results[1].reason = "NOT_FOUND"`
- AND GET /api/activities/Z returns `status = "TODO"`, and GET /api/activities/X/audit with `dev-token-lead` returns an array of length 1 with `actorId = "staff-lead-001"`, `previousStatus = "TODO"`, `newStatus = "DONE"`, `note = "night handover"`

## Out of scope for this sprint
- Any change to `alerts` (the existing `onActivityStatusChanged` listener is reused unchanged).
- Audit writes from the single `PATCH /api/activities/{id}`, `POST` or `DELETE`.
- Paging, filtering or deletion of audit entries.
- Editing `src/app/src/test/java/com/storeops/architecture/**` or `StoreOpsApiIntegrationTest.java`.

## Definition of done
- All ACs MET in the generator summary, each with a test whose @DisplayName starts with its AC id.
- Gate command exits 0 with no protected-path changes and no suppressions.
- All sprint 1 AC tests still pass.
