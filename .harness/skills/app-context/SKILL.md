# Skill: app-context

**Purpose.** A map of what StoreOps already contains, so agents reuse existing types, endpoints
and events instead of inventing parallel ones. Read by **all four agents**. It describes *what
exists*. The rules for changing it live in `architecture-principles`.

---

## 1. Domain in one paragraph

StoreOps is a REST API for retail store operations. Store staff create **programmes**: seasonal
rollouts, compliance drives, refits. They assign operational **activities** (tasks) to staff:
restocking runs, planogram resets, audits, compliance checks. Staff receive in-app **alerts**
when operational events happen, and managers get **reports** of store and regional performance.
Every staff member belongs to exactly one store (`storeId`) in one region (`regionId`). Data is
store-scoped: a caller only sees and changes their own store's programmes and activities.

## 2. Modules

Maven modules live under `src/<module>/`. The base package is `com.storeops.<module>`.

| Module | Owns | Key types (`model`) | Public read API (`api`) |
|---|---|---|---|
| `shared` | Error contract, event bus, domain events | `AppError` + 5 subclasses, `ErrorResponse`, `EventBus`, `DomainEvent`, events | — |
| `staff` | Users, auth tokens, current-user resolution | `User(id, storeId, regionId, role, profile)`, `UserProfile`, `StaffRole` {REGIONAL_MANAGER, STORE_MANAGER, DEPARTMENT_LEAD, ASSOCIATE}, `AuthToken` | `StaffDirectory`: `findById`, `exists`, `authenticate`. `AuthenticatedUser(userId, storeId, regionId, role)` with `isStoreManager()` |
| `programmes` | Store programmes and membership | `Project(id, storeId, name, description, ownerId, members, createdAt)`, `ProjectMember(staffId, role, joinedAt)`, `ProjectRole` {STORE_MANAGER, DEPARTMENT_LEAD, ASSOCIATE} | `ProgrammeDirectory`: `findById`, `exists` |
| `activities` | Operational tasks | `Task(id, storeId, programmeId, title, description, status, priority, category, assigneeId, ownerId, dueDate, createdAt, updatedAt)`, `TaskStatus` {TODO, IN_PROGRESS, DONE, BLOCKED}, `TaskPriority` {LOW, MEDIUM, HIGH, CRITICAL}, `TaskCategory` {RESTOCKING, PLANOGRAM, AUDIT, COMPLIANCE, GENERAL} | `ActivityDirectory`: `findByStore` |
| `alerts` | In-app notifications raised from events | `Notification(id, recipientId, type, channel, status, message, referenceId, createdAt)`, `AlertType` {INVENTORY, SLA_BREACH, SHIFT_HANDOVER, ESCALATION}, `NotificationChannel` {IN_APP, EMAIL}, `NotificationStatus` {UNREAD, READ} | none (nothing may depend on alerts) |
| `reports` | Store and regional metrics (read-only aggregation) | `Report(id, type, status, scopeId, metrics: Map<String, Long>, generatedAt)`, `ReportType` {STORE_SUMMARY, REGIONAL_ROLLUP, DEPARTMENT_PERFORMANCE}, `ReportStatus` {PENDING, READY, FAILED} | none (nothing may depend on reports) |
| `app` | Spring Boot entry point (`com.storeops.StoreOpsApplication`), end-to-end and architecture tests | — | — |

Service classes: `StaffService` implements `StaffDirectory`, `ProgrammeService` implements
`ProgrammeDirectory`, `ActivityService` implements `ActivityDirectory`, plus `AlertService` and
`ReportService` (`generateStoreSummary(storeId)`, `get(id)`). Each module has
`<X>Repository` + `InMemory<X>Repository` backed by a `ConcurrentHashMap`.

## 3. Existing endpoints (base surface — keep their behaviour unchanged)

| Endpoint | Auth | Behaviour |
|---|---|---|
| `GET /api/activities?programmeId=&status=` (alias `GET /api/tasks`) | none | List, both filters optional; an invalid `status` gives 400 `VALIDATION_ERROR` |
| `POST /api/activities` | yes | 201. Defaults: status TODO, priority MEDIUM, category GENERAL. `ownerId` = caller, `storeId` = caller's store. Unknown `programmeId` → 404 `NOT_FOUND`; unknown `assigneeId` → 400 `VALIDATION_ERROR`. Emits `ActivityAssignedEvent` when an assignee is set |
| `GET /api/activities/{id}` | none | 404 `NOT_FOUND` if missing |
| `PATCH /api/activities/{id}` | yes | Partial update of status, priority, category, assigneeId. An empty body gives 400. Another store's task gives 404. Emits `ActivityStatusChangedEvent` on a status change and `ActivityAssignedEvent` on reassignment |
| `DELETE /api/activities/{id}` | yes | 204. Owner or STORE_MANAGER only, else 403 `FORBIDDEN` |
| `GET /api/programmes` | yes | Caller's store only |
| `POST /api/programmes` | yes | 201 |
| `POST /api/programmes/{id}/members` | yes | 201 with the updated programme. ASSOCIATE gets 403. Another store's programme gives 404. Unknown staff gives 404. Staff from another store gives 400. A duplicate gives 409 `CONFLICT`. Emits `ProgrammeMemberAddedEvent` |
| `GET /api/alerts` | yes | Caller's notifications, newest first |

Error body for every failure: `{"code": "...", "message": "...", "statusCode": n}`.

## 4. Events and listeners (`com.storeops.shared.events`)

| Event | Emitted by | Listener (alerts) → effect |
|---|---|---|
| `ActivityAssignedEvent(activityId, title, priority, assigneeId, storeId, occurredAt)` | `ActivityService.create` / `update` | `AlertEventListener.onActivityAssigned` → `SHIFT_HANDOVER` alert to the assignee |
| `ActivityStatusChangedEvent(activityId, title, previousStatus, newStatus, ownerId, storeId, occurredAt)` | `ActivityService.update` | `onActivityStatusChanged` → `ESCALATION` alert to the owner when `newStatus` = BLOCKED |
| `ProgrammeMemberAddedEvent(programmeId, programmeName, staffId, role, occurredAt)` | `ProgrammeService.addMember` | no listener yet |

Delivery is **synchronous** (Spring `ApplicationEventPublisher`). A listener runs inside the
publisher's call, so an exception in a listener fails the originating request.

## 5. Authentication and seed data

Send `Authorization: Bearer <token>`. A route requires auth by declaring a parameter of type
`AuthenticatedUser`. Seeded users (all in `region-north`):

| Token | User id | Store | Role |
|---|---|---|---|
| `dev-token-manager` | `staff-manager-001` | store-001 | STORE_MANAGER |
| `dev-token-lead` | `staff-lead-001` | store-001 | DEPARTMENT_LEAD |
| `dev-token-associate` | `staff-assoc-001` | store-001 | ASSOCIATE |
| `dev-token-other-store` | `staff-manager-002` | store-002 | STORE_MANAGER |

## 6. Where the Section 3.4 features land

| Feature | Owning module | Likely extensions |
|---|---|---|
| Shift handover bulk update (`PATCH /api/activities/bulk-status`) | activities | New route and service method. Per-item result list for partial failure. An audit record owned by activities. Reuse `ActivityStatusChangedEvent` per updated task |
| SLA breach alerting | activities (detects) → alerts (notifies) | New `SlaBreachedEvent` in shared.events, a scheduled check in activities, an alerts listener raising `SLA_BREACH` and then `ESCALATION` |
| Regional rollup (`GET /api/reports/region/{id}`) | reports | First reports `routes`. Needs a read-only `StaffDirectory` method to list the region's stores, and `ActivityDirectory.findByStore` per store. Stores a `REGIONAL_ROLLUP` report |
| Planogram task template (`POST /api/programmes/{id}/templates`) | programmes → activities | Programmes must not create tasks directly. It emits an event that activities consumes |

## 7. Commands

- **Gate** (every check): `./mvnw -B test` (Windows `.\mvnw.cmd -B test`).
- **Single module**: `./mvnw -B test -pl src/<module> -am`
- **Run**: `./mvnw -B install -DskipTests` then `./mvnw -pl src/app spring-boot:run` (port 8080).
