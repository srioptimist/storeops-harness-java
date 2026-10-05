# Skill: coding-conventions

**Purpose.** How StoreOps Java code is written, so that new code looks like the existing code and
passes `-Werror`, Checkstyle and SpotBugs on the first run. Read by the **Generator** only. Each
rule names the check that enforces it, or `review` if only the Evaluator checks it.

---

## CC-1 Package layout per module

```
com.storeops.<module>
├── api/          public read-only interface(s) for other modules   e.g. ActivityDirectory
├── model/        immutable records + enums                          e.g. Task, TaskStatus
├── repository/   <X>Repository interface + InMemory<X>Repository    e.g. TaskRepository
├── service/      <Module>Service + command records                  e.g. ActivityService, NewTask
├── routes/       <Module>Controller + <Verb><Noun>Request records   e.g. ActivityController
└── events/       <Module>EventListener (@Component, @EventListener) e.g. AlertEventListener
```

New domain events go in `com.storeops.shared.events`, never in the module.

**JaCoCo measures exactly `com.storeops.*.service` (≥ 80% lines) and `com.storeops.*.routes`
(≥ 70%).** Don't hide logic in other packages to escape the thresholds; the Evaluator treats that
as gaming.

## CC-2 Naming

| Kind | Pattern | Example |
|---|---|---|
| Controller | `<Module>Controller` in `routes` | `ActivityController` |
| Request body | `<Verb><Noun>Request` record in `routes` | `CreateActivityRequest`, `BulkStatusRequest` |
| Response DTO (only if a model doesn't fit) | `<Noun>Response` record in `routes` | `BulkStatusResponse` |
| Service command | noun record in `service` | `NewTask`, `TaskChanges` |
| Service | `<Module>Service` | `ActivityService` |
| Repository | `<Entity>Repository` + `InMemory<Entity>Repository` | `TaskRepository` |
| Event | `<Subject><PastTenseVerb>Event` | `ActivityStatusChangedEvent`, `SlaBreachedEvent` |
| Listener | `<Module>EventListener`, methods `on<Event>` | `AlertEventListener.onActivityAssigned` |
| Error | `<Meaning>Error` extends `AppError` | `ConflictError` |

Methods are verbs (`create`, `update`, `bulkUpdateStatus`, `listForStore`). Repository queries
are `findBy…`, `findAll`, `save`, `deleteById`. Constants are `UPPER_SNAKE`.

## CC-3 Types and immutability

- **Models, commands, requests and events are `record`s.** Enums for closed sets.
- **Collections in records are copied** in the compact constructor:
  `members = List.copyOf(members);` (`Map.copyOf` for maps). SpotBugs `EI_EXPOSE_REP` fails
  otherwise.
- "Modify" a record by building a new one through a `with…` method, e.g.
  `Task.withChanges(...)` or `Project.withMember(...)`.
- **Timestamps:** `Instant` for points in time, `LocalDate` for business dates (`dueDate`). IDs
  are `String` UUIDs: `UUID.randomUUID().toString()`.
- **Absent values:** use `Optional<T>` from repository and `api` lookups. Never return `null`
  collections.

## CC-4 Spring idioms

- **Constructor injection only**, with `private final` fields. No field `@Autowired`.
- With **two constructors** (a public one for Spring plus a package-private one taking a `Clock`
  for tests), put `@Autowired` on the public one. Precedents: `StaffService` and `ReportService`.
- Stereotypes: `@RestController` for routes, `@Service`, `@Repository` for the in-memory store,
  `@Component` for listeners.
- **Don't make Spring beans `final`.** Don't call overridable methods from constructors
  (SpotBugs `MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR`); write to the maps directly, as
  `InMemoryStaffRepository` does when seeding.
- Inject `EventBus`, not `ApplicationEventPublisher` (AP-4). Inject `StaffDirectory`, not
  `StaffService` (AP-1).
- **Time-dependent logic** (SLA due dates, grace periods) takes a `java.time.Clock` so tests can
  fix the time.
- Scheduled jobs (`@Scheduled`) go in the owning module's `service` package. They call service
  methods and need `@EnableScheduling` on a `@Configuration` in that module.

## CC-5 Controller pattern (routes)

```java
@PatchMapping(BASE + "/bulk-status")
public BulkStatusResponse bulkStatus(AuthenticatedUser caller, @Valid @RequestBody BulkStatusRequest request) {
    return BulkStatusResponse.from(service.bulkUpdateStatus(caller, request.toCommand()));
}
```

- Declaring `AuthenticatedUser caller` makes the endpoint authenticated. Leave it out for public
  reads.
- Use `@ResponseStatus(HttpStatus.CREATED)` for POST-create and `NO_CONTENT` for DELETE.
- Map request → command with a package-private `toCommand()` on the request record. Services
  never see request records.
- Name path variables explicitly: `@PathVariable("id") String id`. Query params use
  `@RequestParam(name = "...", required = false)`.
- No `try/catch` around service calls. `GlobalExceptionHandler` renders `AppError`s.

## CC-6 Service pattern

```java
public BulkStatusResult bulkUpdateStatus(AuthenticatedUser caller, BulkStatusCommand cmd) {
    if (cmd.ids().size() > MAX_BULK) {
        throw new ValidationError("At most " + MAX_BULK + " activity ids per request");
    }
    // per-item: load in caller's store -> rule check -> save -> emit (after save) -> record outcome
}
```

- Order inside an operation: **validate → load (store-scoped) → authorise (role/owner) → mutate
  → save → emit**.
- Error choice follows the `architecture-principles` AP-5 table. Messages name the resource and
  id: `"Activity 'x' not found"`.
- Pass the caller as `AuthenticatedUser`. Scope by `caller.storeId()`. Check roles with
  `caller.role()` / `caller.isStoreManager()`.
- Keep methods under 60 lines (Checkstyle `MethodLength`). Extract `private` helpers named after
  the rule they apply.

## CC-7 Compiler, Checkstyle and SpotBugs (all enforced by `./mvnw -B test`)

| Check | What breaks the build | Do this instead |
|---|---|---|
| `javac -Xlint:all -Werror` | Deprecated API, raw types, unchecked casts, a missing `serialVersionUID` on `AppError` subclasses | Use generics. Use `@MockitoBean`, not the deprecated `@MockBean`. Add `private static final long serialVersionUID = 1L;` |
| Checkstyle (main code only) | Star or unused imports, lines over 120 chars, methods over 60 lines, missing braces, tabs, indentation other than 4 (8 for wrapped lines), `System.out`, `printStackTrace()`, raw `throw new RuntimeException/…`, `ResponseStatusException`, a missing `switch` default, a class with a private constructor not marked `final`, a utility class without a private constructor | Follow the existing files' formatting exactly. Use SLF4J `LoggerFactory.getLogger(X.class)` for logging |
| SpotBugs (max effort, low threshold) | Exposed mutable state, a null dereference on an `Optional` path, ignored return values, dead stores, an inconsistent `equals`/`hashCode` | Records with copied collections. `Optional` chains. No mutable static fields |

Every public type gets a one-line Javadoc stating its role, matching existing files such as
`/** Thread-safe in-memory activity store. */`. Add no other comments unless they explain a
non-obvious rule.

## CC-8 Things the Generator must not do

- Add new dependencies to the root `pom.xml`, change plugin configuration, or add a module (these
  are protected paths).
- Add `@SuppressWarnings`, `@SuppressFBWarnings`, `CHECKSTYLE:OFF`, `@Disabled`, or exclusions.
- Change behaviour of the existing endpoints in `app-context` §3, unless the contract says so.
- Leave `TODO`/`FIXME`, commented-out code, or unused classes in delivered code.
