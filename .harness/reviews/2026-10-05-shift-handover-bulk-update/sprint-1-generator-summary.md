---
sprint: 1
iteration: 1
contract: sprint-1-contract.md
gate_self_check_exit_code: 0
gate_self_check_runs: 1
---
# Sprint 1 Generator Summary — Bulk status endpoint with per-item results, audit write and events

## AC self-check
| AC | Status | Implemented in (file:line) | Proven by (TestClass#method) |
|---|---|---|---|
| AC-1.1 | MET | src/activities/src/main/java/com/storeops/activities/routes/ActivityController.java:54 | ActivityControllerBulkStatusTest#bulkUpdateReturnsPerItemResults, ActivityControllerBulkStatusTest#bulkUpdateRequiresAuthentication |
| AC-1.2 | MET | src/activities/src/main/java/com/storeops/activities/routes/BulkStatusRequest.java:11 (also service guard ActivityService.java:143) | ActivityControllerBulkStatusTest#bulkUpdateRejectsMoreThanFiftyItems, ActivityControllerBulkStatusTest#bulkUpdateRejectsEmptyOrIncompleteItems |
| AC-1.3 | MET | src/activities/src/main/java/com/storeops/activities/service/ActivityService.java:151 | ActivityServiceBulkStatusTest#rejectsDisallowedTargetStatus |
| AC-1.4 | MET | src/activities/src/main/java/com/storeops/activities/service/ActivityService.java:155 | ActivityServiceBulkStatusTest#rejectsDuplicateActivityIds, ActivityServiceBulkStatusTest#rejectsMalformedBatches |
| AC-1.5 | MET | src/activities/src/main/java/com/storeops/activities/service/ActivityService.java:164-166 | ActivityServiceBulkStatusTest#partialFailureUpdatesOnlyOwnStoreItems |
| AC-1.6 | MET | src/activities/src/main/java/com/storeops/activities/service/ActivityService.java:171 | ActivityServiceBulkStatusTest#itemAlreadyInTargetStatusIsUnchanged |
| AC-1.7 | MET | src/activities/src/main/java/com/storeops/activities/service/ActivityService.java:178 | ActivityServiceBulkStatusTest#storesOneAuditEntryPerUpdatedTask |
| AC-1.8 | MET | src/activities/src/main/java/com/storeops/activities/service/ActivityService.java:181 | ActivityServiceBulkStatusTest#emitsOneEventPerUpdatedTaskAfterSaving |

## Files changed
| Path | Layer (model/repository/service/routes/events/test) | Change (NEW/MODIFIED) | Purpose |
|---|---|---|---|
| src/activities/src/main/java/com/storeops/activities/model/ActivityAuditEntry.java | model | NEW | Immutable audit record (id, batchId, activityId, storeId, actorId, previousStatus, newStatus, note, occurredAt) |
| src/activities/src/main/java/com/storeops/activities/model/BulkItemOutcome.java | model | NEW | Enum UPDATED / UNCHANGED / FAILED |
| src/activities/src/main/java/com/storeops/activities/repository/ActivityAuditRepository.java | repository | NEW | Audit store interface (`save`, `findByActivityId`) |
| src/activities/src/main/java/com/storeops/activities/repository/InMemoryActivityAuditRepository.java | repository | NEW | ConcurrentHashMap-backed audit store, sorted by occurredAt |
| src/activities/src/main/java/com/storeops/activities/service/BulkStatusItem.java | service | NEW | Command item (activityId, status) |
| src/activities/src/main/java/com/storeops/activities/service/BulkStatusCommand.java | service | NEW | Command (items in request order, note); copies list |
| src/activities/src/main/java/com/storeops/activities/service/BulkItemResult.java | service | NEW | Per-item result (activityId, outcome, previousStatus, status, auditId, reason) |
| src/activities/src/main/java/com/storeops/activities/service/BulkStatusResult.java | service | NEW | Batch result (batchId, requested, updated, unchanged, failed, results) |
| src/activities/src/main/java/com/storeops/activities/service/ActivityService.java | service | MODIFIED | Constructor takes `ActivityAuditRepository`; new `bulkUpdateStatus` with validate → load (store-scoped) → save task → save audit → emit |
| src/activities/src/main/java/com/storeops/activities/routes/BulkStatusRequest.java | routes | NEW | Body record: `@NotEmpty @Size(max=50)` items, `@Size(max=500)` note, `toCommand()` |
| src/activities/src/main/java/com/storeops/activities/routes/BulkStatusItemRequest.java | routes | NEW | Item record: `@NotBlank activityId`, `@NotNull status` |
| src/activities/src/main/java/com/storeops/activities/routes/ActivityController.java | routes | MODIFIED | New `PATCH /api/activities/bulk-status` (authenticated), returns `BulkStatusResult` with 200 |
| src/activities/src/test/java/com/storeops/activities/routes/ActivityControllerBulkStatusTest.java | test | NEW | Routes tests for AC-1.1, AC-1.2 (+401) |
| src/activities/src/test/java/com/storeops/activities/service/ActivityServiceBulkStatusTest.java | test | NEW | Service/event tests for AC-1.3 to AC-1.8 |
| src/activities/src/test/java/com/storeops/activities/service/ActivityServiceTest.java | test | MODIFIED | Only the `setUp` constructor call (adds `new InMemoryActivityAuditRepository()`) and its import; no assertions changed |

## Events and cross-module calls
| Kind (emit/listen/api-read) | Type | From module | To module |
|---|---|---|---|
| emit | ActivityStatusChangedEvent (existing, reused; one per UPDATED item, after task and audit saves) | activities | alerts (existing listener `onActivityStatusChanged`) |

## Known gaps
- Assumption: other-store items report reason `NOT_FOUND` (same as unknown ids) to hide existence, per AP-5 and AC-1.5.
- Assumption: the note is limited to 500 characters at the route (`@Size(max = 500)`); the contract does not set a limit.
- Assumption: the service also rejects empty batches, over-50 batches, blank ids and null statuses with `ValidationError` (defensive guard in addition to Bean Validation).
- `ActivityService` constructor gained a parameter, so the existing `ActivityServiceTest#setUp` constructor call was updated (wiring only; behaviour and assertions unchanged).
- `ActivityAuditEntry.note` is persisted as given (may be null when no note is sent).
