# Skill: sprint-decomposition

**Purpose.** How the Planner splits a StoreOps feature into sprint contracts, and how it writes
acceptance criteria (ACs) that the Generator can implement and the Evaluator can verify
mechanically. Read by the **Planner** only.

---

## SD-1 Sprint sizing limits

| Limit | Value | Why |
|---|---|---|
| Sprints per feature | 1–3 | Each sprint costs up to 3 Generator and 3 Evaluator invocations. More sprints means more context resets and more cost |
| ACs per sprint | 3–8 | Under 3 usually means the slice is too thin to test end to end. Over 8 doesn't fit one Generator context |
| Modules changed per sprint | ≤ 2 (plus `shared.events` for a new event) | Keeps the Evaluator's diff review within budget |
| Estimated changed lines per sprint | ≤ ~600, tests included | The Evaluator fails checks it can't review fully (ambiguity fallback) |

## SD-2 Where to draw sprint boundaries in StoreOps

Cut along the **event bus**: the owning module's behaviour first, the cross-module reaction next.

1. **Sprint 1: the owning module's core slice.**
   - The endpoint or scheduled job in the owning module.
   - Its service logic, repository changes, all error paths, and the event emission, verified
     with a mocked `EventBus`.
   - It must be demonstrable alone, through the HTTP API.
2. **Sprint 2: the cross-module reaction.**
   - The new or extended listener in the consuming module (usually `alerts`).
   - Any new read method on an `api` interface.
   - An `integration-test` in `src/app` that proves the end-to-end chain through the real bus.
3. **Sprint 3, only if needed: aggregation or follow-on behaviour.**
   - Escalation after a grace period, reporting, or audit queries.

A boundary is valid only if the build is green at the end of the sprint. No sprint may leave a
half-wired event (emitted with no payload contract) or an endpoint without its error paths.

Single-module features (e.g. a pure activities change with no side effects) can be one sprint.

## SD-3 Allowed paths (scope)

Each contract lists the paths the Generator may change. The Evaluator fails `HG-SCOPE` on any
other change.

- Use module-level globs: `src/activities/src/main/java/**`, `src/activities/src/test/java/**`.
- Add `src/shared/src/main/java/com/storeops/shared/events/**` only when the sprint introduces
  an event.
- Add `src/<module>/pom.xml` only when the sprint needs a new module dependency that follows
  `architecture-principles` AP-2.
- Add `src/app/src/test/java/com/storeops/**` for integration tests, but **never**
  `src/app/src/test/java/com/storeops/architecture/**`.
- **Never** list protected paths: `CLAUDE.md`, `.harness/**`, `config/**`, the root `pom.xml`.

## SD-4 Writing acceptance criteria

Format (exactly as below). IDs are `AC-<sprint>.<n>` and never reused after a REVISE.

```
### AC-1.2 — bulk update rejects more than 50 ids  [verify: routes-test]
- GIVEN an authenticated DEPARTMENT_LEAD of store-001
- WHEN they PATCH /api/activities/bulk-status with 51 activity ids
- THEN the response is 400 with code VALIDATION_ERROR
- AND no activity's status changes
```

Rules:
1. **One WHEN per AC.** Two actions means two ACs.
2. **THEN names concrete, observable values:**
   - HTTP status, error `code`, JSON field names and values, enum values;
   - stored state, readable back through an existing endpoint or service method;
   - an emitted event type and its payload fields;
   - an alert `type` for the recipient.
3. **At least one error-path AC per new endpoint**, naming the `AppError` code from
   `architecture-principles` AP-5.
4. **Every cross-module side effect gets an `event-test` AC.** It names the event record and the
   payload fields that must be set.
5. **Store scoping and role rules get their own AC** when the feature involves a role
   (STORE_MANAGER, DEPARTMENT_LEAD, ASSOCIATE) or other-store data. Use the seeded users from
   `app-context` §5.
6. **The verification type is mandatory:**

| Type | Use when the THEN is about |
|---|---|
| `routes-test` | HTTP status, response JSON, request validation, auth (`@WebMvcTest`) |
| `service-test` | business rules, state changes, error selection (plain unit test) |
| `event-test` | an event emitted with the right payload (captured `EventBus.emit`), or a listener's effect |
| `integration-test` | the full chain across modules with the real bus (app module, seeded tokens) |

7. **Banned vague words.** They are subjective, and the Evaluator can't grade them pass/fail:
   *properly, correctly, appropriately, gracefully, robust, efficient, user-friendly, as
   expected, reasonable, works, handles, supports, fast*. Replace each with the observable
   outcome. Write "returns 200 with `results[i].outcome = "FAILED"` and `reason = "NOT_FOUND"`",
   not "handles failures gracefully".

## SD-5 StoreOps AC patterns (copy and adapt)

- **Success:** GIVEN `<seeded user>` and `<existing data>` / WHEN `<METHOD path body>` / THEN
  `<status>` and `<field> = <value>` / AND `<state readable via GET …>`.
- **Validation:** WHEN the body omits or violates `<field rule>` / THEN 400 `VALIDATION_ERROR`
  / AND no state change.
- **Store scoping:** GIVEN a resource of store-002 / WHEN `dev-token-lead` (store-001) calls
  `<path>` / THEN 404 `NOT_FOUND`.
- **Role:** GIVEN `dev-token-associate` / WHEN `<restricted action>` / THEN 403 `FORBIDDEN`.
- **Event:** WHEN `<action>` succeeds / THEN `EventBus.emit` receives `<EventRecord>` with
  `<field> = <value>` (exactly once per `<unit>`).
- **Listener:** GIVEN `<EventRecord(...)>` is published / THEN the recipient's `GET /api/alerts`
  contains `type = <AlertType>` and `referenceId = <id>`.

## SD-6 Spec hygiene checks before writing `STATUS: AWAITING APPROVAL`

- [ ] The feature conflicts with no AP rule; conflicts are raised as `Q-n` with a compliant
      alternative.
- [ ] Every new type is placed in a module and a layer in the impact map.
- [ ] No new Maven module is required (AP-2).
- [ ] Assumptions the Generator may rely on are written as `A-n`, e.g. "A-1: bulk limit is 50
      ids".
- [ ] Each sprint's allowed paths cover every file in its impact-map rows.
