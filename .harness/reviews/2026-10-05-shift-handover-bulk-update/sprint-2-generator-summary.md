---
sprint: 2
iteration: 1
contract: sprint-2-contract.md
gate_self_check_exit_code: 0
gate_self_check_runs: 1
---
# Sprint 2 Generator Summary — Audit read endpoint and end-to-end handover chain

## AC self-check
| AC | Status | Implemented in (file:line) | Proven by (TestClass#method) |
|---|---|---|---|
| AC-2.1 | MET | src/activities/src/main/java/com/storeops/activities/routes/ActivityController.java:55 (route), src/activities/src/main/java/com/storeops/activities/service/ActivityService.java:134 (service) | ActivityControllerAuditTest#auditTrailReturnsEntriesOldestFirst, ActivityControllerAuditTest#auditTrailRequiresAuthentication, ActivityServiceAuditTest#auditTrailListsOwnEntriesOldestFirst |
| AC-2.2 | MET | src/activities/src/main/java/com/storeops/activities/service/ActivityService.java:134 (store-scoped via findInCallerStore) | ActivityServiceAuditTest#auditTrailOfOtherStoreActivityIsNotFound, ActivityServiceAuditTest#auditTrailOfUnknownActivityIsNotFound, ActivityControllerAuditTest#auditTrailOfOtherStoreIsNotFound |
| AC-2.3 | MET | existing auth on PATCH /api/activities/bulk-status (ActivityController.java bulkStatus) | ShiftHandoverBulkUpdateIntegrationTest#bulkUpdateWithoutTokenIsRejected |
| AC-2.4 | MET | existing ActivityService.changeStatus emit of ActivityStatusChangedEvent + unchanged alerts listener | ShiftHandoverBulkUpdateIntegrationTest#bulkBlockedRaisesEscalationForOwner |
| AC-2.5 | MET | ActivityService.java:134 (audit read) + sprint-1 bulk partial-failure logic | ShiftHandoverBulkUpdateIntegrationTest#partialFailureAuditsUpdatedTaskOnly |

## Files changed
| Path | Layer (model/repository/service/routes/events/test) | Change (NEW/MODIFIED) | Purpose |
|---|---|---|---|
| src/activities/src/main/java/com/storeops/activities/service/ActivityService.java | service | MODIFIED | Add `auditTrail(caller, id)`: store-scoped load (NotFoundError for unknown/other-store), then `auditRepository.findByActivityId` (oldest first) |
| src/activities/src/main/java/com/storeops/activities/routes/ActivityController.java | routes | MODIFIED | Add authenticated `GET /api/activities/{id}/audit` returning `List<ActivityAuditEntry>` |
| src/activities/src/test/java/com/storeops/activities/routes/ActivityControllerAuditTest.java | test | NEW | Routes tests for AC-2.1 (fields, order, 401) and AC-2.2 (404 rendering) |
| src/activities/src/test/java/com/storeops/activities/service/ActivityServiceAuditTest.java | test | NEW | Service tests for AC-2.2 (other store / unknown -> NOT_FOUND) and AC-2.1 ordering/filtering |
| src/app/src/test/java/com/storeops/ShiftHandoverBulkUpdateIntegrationTest.java | test | NEW | Integration tests AC-2.3, AC-2.4, AC-2.5 with seeded tokens and real event bus |

## Events and cross-module calls
| Kind (emit/listen/api-read) | Type | From module | To module |
|---|---|---|---|
| emit (existing, unchanged) | ActivityStatusChangedEvent | activities | alerts (existing onActivityStatusChanged listener, unchanged) |

No new events, listeners or cross-module calls in this sprint.

## Known gaps
- None. Assumption: the audit endpoint requires authentication (AC-2.1 "authenticated", "store-scoped"); an unauthenticated GET returns 401 UNAUTHORIZED.
