# Skill: architecture-principles

**Purpose.** The non-negotiable StoreOps architecture rules, with what each one prohibits, how it
is enforced, and what compliant and violating code looks like. Read by the **Planner, Generator
and Evaluator**. Rule ids (`AP-x`) are cited in evaluator findings. Each rule traces to one of the
four failure modes the client's standards team saw in the earlier AI experiment:

| FM | Failure mode |
|---|---|
| FM1 | Direct imports from another module's repository, bypassing the service boundary and event bus |
| FM2 | Raw `Error` throws in services, bypassing the `AppError` hierarchy |
| FM3 | Tests that assert HTTP status codes but not business-rule compliance |
| FM4 | Missing event bus integration: state written directly into sibling modules |

---

## AP-1 Module boundary: `api` and `model` only (FM1)

Code in module A may use module B only through `com.storeops.B.api..` and `com.storeops.B.model..`.
**Never** import `B.repository`, `B.service`, `B.routes` or `B.events`.

```java
// ✅ activities reads staff through the read API
private final StaffDirectory staffDirectory;          // com.storeops.staff.api
if (!staffDirectory.exists(assigneeId)) { ... }

// ❌ hard-gate failure (HG-ARCH): repository of another module
import com.storeops.staff.repository.StaffRepository;
// ❌ also a failure: another module's concrete service
import com.storeops.programmes.service.ProgrammeService;
```

- **Enforced by:** ArchUnit `crossModuleAccessGoesThroughPublicApiOnly`.
- **Escape hatch:** none. If module A needs data that B's `api` doesn't expose, add a **read-only**
  method to B's `api` interface and implement it in B's service (AP-3).

## AP-2 Allowed module dependency graph (FM1)

```
shared  ←  staff  ←  programmes  ←  activities  ←  reports
             ↑
             └── alerts
```

- A module's `pom.xml` may only declare dependencies that follow these arrows. `reports` may add
  `storeops-staff` or `storeops-programmes`, since that is a read dependency on modules lower in
  the graph.
- **Nothing may depend on `alerts` or `reports`.** They are reached only through events (AP-4).
- No cycles. The Maven reactor fails on a cycle, and ArchUnit's `modulesAreFreeOfCycles` checks
  the packages.
- **No new Maven modules.** Adding one requires editing the root `pom.xml`, which is a protected
  path. A new concern goes into the existing module that owns it.

## AP-3 Staff (users) is read-only for other modules; `api` methods never write

- `StaffDirectory`, `ProgrammeDirectory` and `ActivityDirectory` expose queries only:
  `find*`, `exists*`, `list*`, `authenticate`.
- A new `api` method must not change state. A write method on an `api` interface is a blocking
  review finding even though ArchUnit allows it.
- Models crossing a module boundary are immutable records. Collections are copied in the compact
  constructor (`List.copyOf`, `Map.copyOf`).

## AP-4 Cross-module side effects through `EventBus.emit` only (FM4)

When something in module A must cause a change in module B (an alert, a report, a task created
from a template), A **emits an event** and B **listens**.

```java
// ✅ in activities.service — publisher knows nothing about alerts
eventBus.emit(new SlaBreachedEvent(task.id(), task.assigneeId(), task.storeId(), Instant.now()));

// ✅ in alerts.events — listener delegates to its own service
@EventListener
public void onSlaBreached(SlaBreachedEvent event) {
    alertService.raise(event.assigneeId(), AlertType.SLA_BREACH, "...", event.activityId());
}

// ❌ hard-gate failure (HG-ARCH): direct call into alerts
alertService.raise(...);              // from activities
// ❌ blocking finding: bypasses the bus abstraction
applicationEventPublisher.publishEvent(...);   // only SpringEventBus may use the Spring publisher
```

Event rules:
1. Events are records in `com.storeops.shared.events` that implement `DomainEvent` (with
   `Instant occurredAt`).
2. Payload fields are `String`, primitives, `Instant` or `LocalDate` only. **Never** module
   types: enums travel as `name()` strings. This stops `shared` depending on a module.
3. Inject `EventBus` (the interface), never `SpringEventBus` or `ApplicationEventPublisher`.
4. Emit **after** the owning repository write succeeds, so listeners never see state that
   wasn't saved.
5. Listeners live in `<module>.events`, are `@Component`s, and only call their own module's
   service.
6. Delivery is synchronous: a listener must not throw for expected business conditions. It
   should log and skip instead, because throwing would fail the publisher's HTTP request.

**Enforced by:** ArchUnit `alertsAndReportsAreReachedViaEventBusOnly`, plus Evaluator review for
rules 2–6.

## AP-5 Error contract: `AppError` only (FM2)

Services and routes throw **only** subclasses of `com.storeops.shared.error.AppError`.
`GlobalExceptionHandler` renders `{"code", "message", "statusCode"}`.

| Situation | Throw | code / status |
|---|---|---|
| Resource in the **path** doesn't exist, or belongs to another store | `NotFoundError(resource, id)` | `NOT_FOUND` / 404 |
| Request invalid: empty update, bad combination, an unacceptable referenced id | `ValidationError(msg)` | `VALIDATION_ERROR` / 400 |
| No or invalid bearer token | `UnauthorizedError` (raised by the resolver; services don't throw it) | `UNAUTHORIZED` / 401 |
| Authenticated but the role or ownership doesn't allow it | `ForbiddenError(msg)` | `FORBIDDEN` / 403 |
| Conflicts with current state (duplicate, illegal transition) | `ConflictError(msg)` | `CONFLICT` / 409 |

Rules:
- **Store scoping hides existence.** Another store's resource gives `NotFoundError`, not
  `ForbiddenError`. This is the existing precedent in `ActivityService.findInCallerStore` and
  `ProgrammeService.addMember`.
- **Existing exceptions, don't change them:** an unknown `programmeId` on `POST /api/activities`
  and an unknown `staffId` on `POST /api/programmes/{id}/members` return `NOT_FOUND`. New
  endpoints follow the table above.
- **Never** use `throw new RuntimeException / IllegalArgumentException / IllegalStateException /
  UnsupportedOperationException`, `ResponseStatusException`, or `ResponseEntity.status(4xx)` in
  routes.
- **Validation.** Bean Validation (`@NotBlank`, `@Size`, `@NotNull`, `@NotEmpty`) on request
  records gives `VALIDATION_ERROR` automatically. Business validation in the service throws
  `ValidationError`.
- **New `AppError` subclass:** only when no existing one fits the contract's status code. It goes
  in `shared.error`, has a `serialVersionUID`, and gets a stable upper-snake `code`.
- **Partial success is not an error.** For example, a bulk update with some failed items returns
  200 with a per-item result list. Don't throw for an item-level failure.

**Enforced by:** the Checkstyle `RegexpSingleline` raw-throw ban, ArchUnit
`servicesAndRoutesOnlyThrowAppErrors`, and the Evaluator checking each contract error case's
`code`.

## AP-6 Layer separation

| Layer | Package | May | Must not |
|---|---|---|---|
| Routes | `<module>.routes` | Map HTTP to a command, declare `AuthenticatedUser`, `@Valid` request records, call **one** service method, set the status | Contain business rules or branch on domain state; touch repositories; catch `AppError` |
| Service | `<module>.service` | Hold business rules, call its own repository, call other modules' `api`, emit events, throw `AppError` | Use `org.springframework.web..` or `..http..` types; know about routes |
| Repository | `<module>.repository` | Store and query its own module's records | Emit events, call services, use web types, apply business rules beyond filtering |
| Events (listeners) | `<module>.events` | Receive `DomainEvent`s and call its own service | Contain business logic beyond mapping the event to a service call |

**Enforced by:** ArchUnit `routesDoNotAccessRepositories`,
`repositoriesDoNotDependOnWebOrServiceLayers`, `servicesDoNotDependOnWebLayer`.

## AP-7 Reports are read-only (FM4)

- `reports` reads through `ActivityDirectory`, `StaffDirectory` and `ProgrammeDirectory` only.
- It writes **only** to `ReportRepository`. It never emits events that ask other modules to
  change state.
- **Enforced by:** ArchUnit `reportsOnlyReadFromOtherModules`. That rule also forbids `reports`
  from referencing `com.storeops.shared.events` at all, so reports can **neither emit nor listen**.
  A report requested over HTTP is generated and stored synchronously by `ReportService`. If a
  feature wording asks reports to react to an event (e.g. "triggers a REGIONAL_ROLLUP via the
  event bus"), the Planner raises it as a blocking `Q-n`. It does not plan around the rule.

## AP-8 Gates are not negotiable

Never change `config/**`, the root `pom.xml`, `ArchitectureRulesTest`, `CLAUDE.md` or
`.harness/**` to make a build pass. Never add suppressions (`@SuppressWarnings`,
`@SuppressFBWarnings`, `CHECKSTYLE:OFF`, `@Disabled`, `<skip>`). If a rule seems wrong, report it
as a known gap. Changing the rule is a human decision.
