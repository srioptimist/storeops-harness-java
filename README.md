# StoreOps API (Java 17 / Spring Boot 3.5 / Maven multi-module)

Retail store operations management REST API. Stub implementations with in-memory storage —
enough structure to compile, test, lint and run. Capstone reference codebase; not for production.

## Quick start

```bash
./mvnw test                                   # Checkstyle + SpotBugs + JUnit5/MockMvc + ArchUnit + JaCoCo
./mvnw install -DskipTests                    # build src/app/target/storeops.jar
./mvnw -pl src/app spring-boot:run            # after install; serves http://localhost:8080
curl http://localhost:8080/api/tasks          # -> 200 []
```

## Layout

```
pom.xml                      parent POM: plugins, lint, coverage gates
config/checkstyle/           Checkstyle rules (incl. "no raw exception throws")
config/spotbugs/             SpotBugs exclusions (justified, minimal)
src/
  shared/      AppError hierarchy, GlobalExceptionHandler, EventBus + domain events
  staff/       users, auth tokens, AuthenticatedUser resolver     (read-only API for others)
  programmes/  store programmes + membership
  activities/  operational activities (tasks)
  alerts/      in-app alerts, driven purely by events
  reports/     store/region metrics, read-only aggregation (no HTTP routes yet)
  app/         Spring Boot entry point, end-to-end + architecture tests
```

Each domain module uses the packages `routes` → `service` → `repository`, plus `model`
(immutable records/enums) and `api` (the read-only interface other modules may use).
Tests live in each module's `src/test/java`, mirroring the main packages.

### Module dependency graph (acyclic — Maven refuses cycles)

```
shared <- staff <- programmes <- activities <- reports
            ^                                  
            +------- alerts   (listens to events only; nobody depends on alerts or reports)
```

## Architecture rules and how they are enforced

| Rule | Enforcement (all run in `mvn test`) |
|---|---|
| No circular imports | Maven reactor + ArchUnit `modulesAreFreeOfCycles` |
| Module boundary: cross-module access only via `<module>.api` / `<module>.model` | ArchUnit `crossModuleAccessGoesThroughPublicApiOnly` |
| Notifications via event bus only | `EventBus.emit(...)` in `shared.events`; ArchUnit `alertsAndReportsAreReachedViaEventBusOnly` |
| Staff/users read-only for other modules | `StaffDirectory` exposes reads only; records are immutable |
| Layer separation | ArchUnit: routes ↛ repository, repository ↛ web/service/events, service ↛ web |
| Error contract (`AppError` with `code`, `message`, `statusCode`) | Checkstyle regexp bans `throw new RuntimeException/IllegalStateException/...`; ArchUnit `servicesAndRoutesOnlyThrowAppErrors` |
| Reports read-only | ArchUnit `reportsOnlyReadFromOtherModules` |
| Coverage: service ≥ 80 %, routes ≥ 70 %, overall ≥ 70 % (lines) | JaCoCo `check` per module (bound to `test` phase) |

Error hierarchy (`com.storeops.shared.error`): `AppError` → `ValidationError` (400),
`UnauthorizedError` (401), `ForbiddenError` (403), `NotFoundError` (404), `ConflictError` (409).
Every error response is `{"code": "...", "message": "...", "statusCode": n}`.

## Endpoints (Section 3.6)

| Method | Path | Auth | Notes |
|---|---|---|---|
| GET | `/api/activities?programmeId=&status=` | – | also aliased as `GET /api/tasks` |
| POST | `/api/activities` | ✓ | 201; emits `ActivityAssignedEvent` if assignee set |
| GET | `/api/activities/{id}` | – | 404 `NOT_FOUND` |
| PATCH | `/api/activities/{id}` | ✓ | status / priority / category / assigneeId; emits `ActivityStatusChangedEvent` |
| DELETE | `/api/activities/{id}` | ✓ | 204; owner or STORE_MANAGER only, else 403 |
| GET | `/api/programmes` | ✓ | programmes of the caller's store |
| POST | `/api/programmes` | ✓ | 201 |
| POST | `/api/programmes/{id}/members` | ✓ | 201; 409 on duplicate; emits `ProgrammeMemberAddedEvent` |
| GET | `/api/alerts` | ✓ | caller's alerts, newest first |

Authentication: `Authorization: Bearer <token>`. Seeded dev tokens (store-001 unless noted):
`dev-token-manager` (STORE_MANAGER), `dev-token-lead` (DEPARTMENT_LEAD),
`dev-token-associate` (ASSOCIATE), `dev-token-other-store` (STORE_MANAGER, store-002).

Event → alert wiring (in `alerts.events.AlertEventListener`):
`ActivityAssignedEvent` → `SHIFT_HANDOVER` alert to the assignee;
`ActivityStatusChangedEvent` to `BLOCKED` → `ESCALATION` alert to the owner.
